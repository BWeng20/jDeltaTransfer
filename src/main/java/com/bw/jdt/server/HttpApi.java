package com.bw.jdt.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ChunkStore;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Hash;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.proto.Wire;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP surface of the server.
 *
 * <pre>
 *   GET  /                            human readable version list
 *   GET  /api/config                  block size limits the client has to honour
 *   GET  /api/versions                all versions with their SHA-256 hashes
 *   GET  /api/versions/{id}           one version
 *   GET  /api/versions/{id}/blueprint the rebuild recipe (gzipped)
 *   POST /api/versions/{id}/blocks    requested blocks, framed (the delta)
 *   GET  /api/versions/{id}/full      the complete archive, framed
 *   POST /api/rescan                  ingest newly dropped archives
 *   GET  /health
 * </pre>
 */
public final class HttpApi implements AutoCloseable {

    private final VersionStore store;
    private final int maxBlockSize;
    private final HttpServer server;
    private final ExecutorService pool;
    private final ObjectMapper json = new ObjectMapper();
    private final VersionStore.Log log;

    public HttpApi(VersionStore store, String bindHost, int port, int maxBlockSize,
                   int threads, VersionStore.Log log) throws IOException {
        this.store = store;
        this.maxBlockSize = maxBlockSize;
        this.log = log;
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 64);
        this.pool = Executors.newFixedThreadPool(threads);
        server.setExecutor(pool);
        server.createContext("/", wrap(this::handleRoot));
        server.createContext("/health", wrap(e -> sendText(e, 200, "ok\n")));
        server.createContext("/api/config", wrap(this::handleConfig));
        server.createContext("/api/versions", wrap(this::handleVersions));
        server.createContext("/api/rescan", wrap(this::handleRescan));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    // -------------------------------------------------------------- handlers

    private void handleConfig(HttpExchange e) throws IOException {
        Chunker.Params p = store.chunkParams();
        ObjectNode n = json.createObjectNode();
        n.put("hashAlgorithm", Hashes.ALGORITHM);
        n.put("maxBlockSize", maxBlockSize);
        n.put("blockSizeMin", p.min());
        n.put("blockSizeAvg", p.avg());
        n.put("blockSizeMax", p.max());
        n.put("storedBlocks", store.blocks().chunkCount());
        n.put("storedBlockBytes", store.blocks().storedBytes());
        sendJson(e, 200, n);
    }

    private void handleVersions(HttpExchange e) throws IOException {
        String path = e.getRequestURI().getPath();
        String rest = path.substring("/api/versions".length());
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            ArrayNode arr = json.createArrayNode();
            for (VersionStore.VersionInfo v : store.versions()) {
                arr.add(toJson(v));
            }
            ObjectNode root = json.createObjectNode();
            root.put("hashAlgorithm", Hashes.ALGORITHM);
            root.put("count", arr.size());
            root.set("versions", arr);
            sendJson(e, 200, root);
            return;
        }

        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String action = slash < 0 ? "" : rest.substring(slash + 1);
        VersionStore.VersionInfo info = store.version(id);
        if (info == null) {
            sendText(e, 404, "no such version: " + id + "\n");
            return;
        }
        switch (action) {
            case "" -> sendJson(e, 200, toJson(info));
            case "blueprint" -> handleBlueprint(e, info);
            case "blocks" -> handleBlocks(e, info);
            case "full" -> handleFull(e, info);
            default -> sendText(e, 404, "unknown action: " + action + "\n");
        }
    }

    private void handleBlueprint(HttpExchange e, VersionStore.VersionInfo info) throws IOException {
        byte[] bp = store.blueprintBytes(info.id());
        if (bp == null) {
            sendText(e, 404, "no blueprint for " + info.id() + "\n");
            return;
        }
        e.getResponseHeaders().add("Content-Type", Wire.CONTENT_TYPE_BLUEPRINT);
        e.getResponseHeaders().add("X-JDT-Blueprint-SHA256", info.blueprintSha256());
        e.getResponseHeaders().add("X-JDT-Archive-SHA256", info.sha256());
        e.sendResponseHeaders(200, bp.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(bp);
        }
    }

    /** Streams exactly the blocks the client asked for, each one framed with its hash. */
    private void handleBlocks(HttpExchange e, VersionStore.VersionInfo info) throws IOException {
        if (!"POST".equalsIgnoreCase(e.getRequestMethod())) {
            sendText(e, 405, "POST a hash list to this endpoint\n");
            return;
        }
        List<Hash> wanted;
        try (InputStream in = new BufferedInputStream(e.getRequestBody(), 1 << 16)) {
            wanted = Wire.readHashList(in);
        }
        ChunkStore blocks = store.blocks();
        e.getResponseHeaders().add("Content-Type", Wire.CONTENT_TYPE_BLOCKS);
        e.getResponseHeaders().add("X-JDT-Max-Block-Size", Integer.toString(maxBlockSize));
        e.sendResponseHeaders(200, 0);
        long sent;
        try (OutputStream raw = e.getResponseBody();
             Wire.BlockWriter writer = new Wire.BlockWriter(new java.io.BufferedOutputStream(raw, 1 << 16),
                     maxBlockSize)) {
            for (Hash h : wanted) {
                if (!blocks.contains(h)) {
                    // The blueprint the client holds and the store disagree; abort loudly.
                    throw new IOException("client asked for unknown block " + h);
                }
                writer.write(h, blocks.get(h));
            }
            sent = writer.bytes();
        }
        log.info("served " + wanted.size() + " blocks (" + VersionStore.human(sent) + ") of " + info.id());
    }

    /** Streams the full archive as framed blocks, cut by the same content defined chunker. */
    private void handleFull(HttpExchange e, VersionStore.VersionInfo info) throws IOException {
        Path file = store.archiveFile(info.id());
        e.getResponseHeaders().add("Content-Type", Wire.CONTENT_TYPE_BLOCKS);
        e.getResponseHeaders().add("X-JDT-Archive-SHA256", info.sha256());
        e.getResponseHeaders().add("X-JDT-Archive-Size", Long.toString(info.size()));
        e.getResponseHeaders().add("X-JDT-Max-Block-Size", Integer.toString(maxBlockSize));
        e.sendResponseHeaders(200, 0);

        Chunker chunker = new Chunker(store.chunkParams());
        try (OutputStream raw = e.getResponseBody();
             Wire.BlockWriter writer = new Wire.BlockWriter(new java.io.BufferedOutputStream(raw, 1 << 16),
                     maxBlockSize);
             OutputStream chunked = chunker.newOutputStream(
                     (buf, off, len) -> writer.write(Hashes.of(buf, off, len), buf, off, len))) {
            if (file != null && Files.exists(file)) {
                try (InputStream in = ByteSource.ofFile(file).openStream()) {
                    in.transferTo(chunked);
                }
            } else {
                // The original file is gone: rebuild it from the blueprint straight into the
                // response, chunked on the fly, without staging it on disk first.
                rebuildInto(info, chunked);
            }
        }
        log.info("served full archive " + info.id() + " (" + VersionStore.human(info.size()) + ")");
    }

    /** Fallback for stores that no longer keep the original archive file. */
    private void rebuildInto(VersionStore.VersionInfo info, OutputStream out) throws IOException {
        var bp = store.blueprint(info.id());
        if (bp == null) {
            throw new IOException("neither archive file nor blueprint available for " + info.id());
        }
        try (var wd = com.bw.jdt.core.WorkDir.createTemp("jdt-rebuild-")) {
            new com.bw.jdt.core.Reassembler(store.blocks(), wd).writeVerified(bp, out);
        }
    }

    private void handleRescan(HttpExchange e) throws IOException {
        if (!"POST".equalsIgnoreCase(e.getRequestMethod())) {
            sendText(e, 405, "POST to trigger a rescan\n");
            return;
        }
        int n = store.scan(log);
        ObjectNode root = json.createObjectNode();
        root.put("ingested", n);
        root.put("total", store.versions().size());
        sendJson(e, 200, root);
    }

    private void handleRoot(HttpExchange e) throws IOException {
        if (!"/".equals(e.getRequestURI().getPath())) {
            sendText(e, 404, "not found\n");
            return;
        }
        Chunker.Params p = store.chunkParams();
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset=\"utf-8\"><title>jDeltaTransfer</title>");
        sb.append("<style>body{font:14px system-ui,sans-serif;margin:2rem;max-width:70rem}"
                + "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #ddd;"
                + "padding:.4rem .6rem;text-align:left}code{font-size:12px}"
                + "td.h{font-family:ui-monospace,monospace;font-size:11px;word-break:break-all}</style>");
        sb.append("<h1>jDeltaTransfer server</h1>");
        sb.append("<p>hash algorithm <code>").append(Hashes.ALGORITHM).append("</code>, ")
                .append("block size min ").append(p.min()).append(" / avg ").append(p.avg())
                .append(" / max ").append(p.max()).append(" bytes, hard limit ")
                .append(maxBlockSize).append(" bytes.</p>");
        sb.append("<p>Block store: ").append(store.blocks().chunkCount()).append(" distinct blocks, ")
                .append(VersionStore.human(store.blocks().storedBytes())).append(".</p>");
        sb.append("<table><tr><th>version</th><th>size</th><th>SHA-256</th>"
                + "<th>blocks</th><th>ingested</th></tr>");
        for (VersionStore.VersionInfo v : store.versions()) {
            sb.append("<tr><td><a href=\"/api/versions/").append(escape(v.id())).append("\">")
                    .append(escape(v.id())).append("</a></td>")
                    .append("<td>").append(VersionStore.human(v.size())).append("</td>")
                    .append("<td class=\"h\">").append(escape(v.sha256())).append("</td>")
                    .append("<td>").append(v.blockRefs()).append(" / ").append(v.distinctBlocks())
                    .append(" distinct</td>")
                    .append("<td>").append(escape(v.ingestedAt())).append("</td></tr>");
        }
        sb.append("</table>");
        sb.append("<p>JSON: <a href=\"/api/versions\">/api/versions</a>, "
                + "<a href=\"/api/config\">/api/config</a></p>");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        e.sendResponseHeaders(200, body.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(body);
        }
    }

    // --------------------------------------------------------------- helpers

    private ObjectNode toJson(VersionStore.VersionInfo v) {
        ObjectNode n = json.createObjectNode();
        n.put("id", v.id());
        n.put("fileName", v.fileName());
        n.put("size", v.size());
        n.put("sizeHuman", VersionStore.human(v.size()));
        n.put("hashAlgorithm", Hashes.ALGORITHM);
        n.put("sha256", v.sha256());
        n.put("blueprintSha256", v.blueprintSha256());
        n.put("blueprintBytes", v.blueprintBytes());
        n.put("blockRefs", v.blockRefs());
        n.put("distinctBlocks", v.distinctBlocks());
        n.put("containers", v.containers());
        n.put("opaqueContainers", v.opaqueContainers());
        n.put("ingestedAt", v.ingestedAt());
        return n;
    }

    private HttpHandler wrap(Handler handler) {
        return exchange -> {
            String line = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            try {
                handler.handle(exchange);
            } catch (IOException | RuntimeException ex) {
                log.warn(line + " failed: " + ex);
                try {
                    sendText(exchange, 500, "error: " + ex.getMessage() + "\n");
                } catch (IOException | IllegalStateException ignored) {
                    // Response already started; nothing left to say.
                }
            } finally {
                exchange.close();
            }
        };
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange e) throws IOException;
    }

    private void sendJson(HttpExchange e, int status, Object body) throws IOException {
        byte[] data = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        e.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, data.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(data);
        }
    }

    private void sendText(HttpExchange e, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        e.sendResponseHeaders(status, data.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(data);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static {
        // The JDK http server logs verbosely on client disconnects; keep the console readable.
        java.util.logging.Logger.getLogger("com.sun.net.httpserver")
                .setLevel(java.util.logging.Level.WARNING);
        Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
    }
}
