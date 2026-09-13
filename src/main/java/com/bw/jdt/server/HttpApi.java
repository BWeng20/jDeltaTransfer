package com.bw.jdt.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ChunkStore;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Hash;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.proto.Tls;
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
 * HTTP surface of the server, split over two ports so the administrative half can be kept off
 * the network with nothing more than a firewall rule -- or, by default, by never leaving loopback.
 *
 * <p>The transfer port carries exactly what a client needs and nothing else:
 *
 * <pre>
 *   GET  /api/config                  block size limits the client has to honour
 *   GET  /api/versions                all versions with their SHA-256 hashes
 *   GET  /api/versions/{id}           one version
 *   GET  /api/versions/{id}/blueprint the rebuild recipe (gzipped)
 *   POST /api/versions/{id}/blocks    requested blocks, framed (the delta)
 *   GET  /api/versions/{id}/full      the complete archive, framed
 *   GET  /health
 * </pre>
 *
 * <p>The admin port serves all of the above -- so the overview page's links work and an operator
 * needs only one address -- plus:
 *
 * <pre>
 *   GET  /                            human readable version list
 *   POST /api/rescan                  ingest newly dropped archives
 * </pre>
 *
 * <p>Keeping the read API on both is deliberate: it exposes nothing the transfer port does not
 * already, and without it the overview page would link to dead addresses. What the transfer port
 * must not carry is {@code /api/rescan}, which is unauthenticated and starts minutes of work.
 */
public final class HttpApi implements AutoCloseable {

    /**
     * Where the two servers listen.
     *
     * @param bindHost      transfer port bind address
     * @param port          transfer port, 0 for an ephemeral one
     * @param adminBindHost admin bind address; loopback by default, so the admin half is
     *                      unreachable from elsewhere even with no firewall in place
     * @param adminPort     admin port, 0 for an ephemeral one, or -1 to not open it at all
     */
    public record Endpoints(String bindHost, int port, String adminBindHost, int adminPort) {

        public static final int NO_ADMIN = -1;

        /** Transfer port only; nothing administrative is reachable. */
        public static Endpoints transferOnly(String bindHost, int port) {
            return new Endpoints(bindHost, port, "127.0.0.1", NO_ADMIN);
        }

        public boolean adminEnabled() {
            return adminPort != NO_ADMIN;
        }
    }

    private final VersionStore store;
    private final int maxBlockSize;
    private final int compressionLevel;
    private final HttpServer server;
    private final HttpServer adminServer;
    private final ExecutorService pool;
    private final ExecutorService adminPool;
    private final ObjectMapper json = new ObjectMapper();
    private final VersionStore.Log log;
    private final Tls.ServerConfig tls;
    private final String scheme;

    public HttpApi(VersionStore store, String bindHost, int port, int maxBlockSize,
                   int threads, VersionStore.Log log) throws IOException {
        this(store, bindHost, port, maxBlockSize, threads, Wire.DEFAULT_COMPRESSION_LEVEL, log);
    }

    /**
     * @param compressionLevel gzip level for the block stream, or 0 to always serve it
     *                         uncompressed even when the client offers to accept compression
     */
    public HttpApi(VersionStore store, String bindHost, int port, int maxBlockSize,
                   int threads, int compressionLevel, VersionStore.Log log) throws IOException {
        this(store, Endpoints.transferOnly(bindHost, port), maxBlockSize, threads,
                compressionLevel, log);
    }

    public HttpApi(VersionStore store, Endpoints endpoints, int maxBlockSize, int threads,
                   int compressionLevel, VersionStore.Log log) throws IOException {
        this(store, endpoints, maxBlockSize, threads, compressionLevel, null, log);
    }

    /**
     * @param tls server certificate and, optionally, the client certificates to accept. When null
     *            both ports speak plain HTTP; when given, both speak HTTPS -- an admin port on a
     *            management interface needs encryption at least as much as the transfer port.
     */
    public HttpApi(VersionStore store, Endpoints endpoints, int maxBlockSize, int threads,
                   int compressionLevel, Tls.ServerConfig tls, VersionStore.Log log)
            throws IOException {
        this.store = store;
        this.tls = tls;
        this.scheme = tls == null ? "http" : "https";
        this.maxBlockSize = maxBlockSize;
        this.compressionLevel = compressionLevel;
        this.log = log;

        this.server = bind("transfer", endpoints.bindHost(), endpoints.port(), 64);
        this.pool = Executors.newFixedThreadPool(threads);
        server.setExecutor(pool);
        addSharedContexts(server);
        server.createContext("/api/config", wrap(this::handleClientConfig));
        // Anything administrative is simply absent here, not merely refused.
        server.createContext("/", wrap(e -> sendText(e, 404,
                "not found. This is the transfer port; see /api/versions\n")));

        if (endpoints.adminEnabled()) {
            if (isWildcard(endpoints.adminBindHost())) {
                log.warn("the admin port is bound to " + endpoints.adminBindHost()
                        + ", so it is reachable from every interface. That undoes the reason it is"
                        + " a separate port; use --admin-bind with a specific address, or make sure"
                        + " a firewall rule covers it.");
            }
            this.adminServer = bind("admin", endpoints.adminBindHost(), endpoints.adminPort(), 16);
            // Its own small pool: a rescan blocks a thread for minutes and must not eat into the
            // threads serving transfers.
            this.adminPool = Executors.newFixedThreadPool(2);
            adminServer.setExecutor(adminPool);
            addSharedContexts(adminServer);
            adminServer.createContext("/api/config", wrap(this::handleAdminConfig));
            adminServer.createContext("/", wrap(this::handleRoot));
            adminServer.createContext("/api/rescan", wrap(this::handleRescan));
        } else {
            this.adminServer = null;
            this.adminPool = null;
        }
    }

    /**
     * Binds one of the two servers, turning the JDK's terse failures into something an operator
     * can act on. Both ports can be given any local address, which is the point when the admin
     * half belongs on a management interface rather than on loopback.
     */
    private HttpServer bind(String role, String host, int port, int backlog)
            throws IOException {
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new IOException("cannot resolve the " + role + " bind address '" + host + "'");
        }
        try {
            if (tls == null) {
                return HttpServer.create(address, backlog);
            }
            HttpsServer https = HttpsServer.create(address, backlog);
            https.setHttpsConfigurator(configurator());
            return https;
        } catch (java.net.BindException e) {
            throw new IOException("cannot bind the " + role + " port to " + host + ":" + port
                    + " -- " + e.getMessage()
                    + ". Either the address does not exist on this host or the port is in use.", e);
        }
    }

    /**
     * Restricts every connection to TLS 1.2 and 1.3, and demands a client certificate when asked
     * to. The JDK's default parameters would otherwise decide, and those change between releases.
     */
    private HttpsConfigurator configurator() throws IOException {
        javax.net.ssl.SSLContext ctx = Tls.serverContext(tls);
        return new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                javax.net.ssl.SSLParameters p = ctx.getDefaultSSLParameters();
                p.setProtocols(Tls.PROTOCOLS);
                // Must go on the SSLParameters object, not on params: once setSSLParameters is
                // used, the JDK applies only that object and silently ignores
                // params.setNeedClientAuth. Getting this wrong makes client certificates look
                // configured while nothing is actually demanded.
                p.setNeedClientAuth(tls.requireClientCert());
                params.setSSLParameters(p);
            }
        };
    }

    private static boolean isWildcard(String host) {
        try {
            return java.net.InetAddress.getByName(host).isAnyLocalAddress();
        } catch (java.net.UnknownHostException e) {
            // Unresolvable hosts are reported by bind() with a better message than a guess here.
            return false;
        }
    }

    /**
     * Contexts both ports share. {@code /api/config} is deliberately not among them: the two
     * ports answer it with different documents, and a context cannot be replaced once added.
     */
    private void addSharedContexts(HttpServer target) {
        target.createContext("/health", wrap(e -> sendText(e, 200, "ok\n")));
        target.createContext("/api/versions", wrap(this::handleVersions));
    }

    public void start() {
        server.start();
        if (adminServer != null) {
            adminServer.start();
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** "http" or "https", so log lines and the overview page name the right scheme. */
    public String scheme() {
        return scheme;
    }

    /** @return the admin port, or {@link Endpoints#NO_ADMIN} when it was not opened */
    public int adminPort() {
        return adminServer == null ? Endpoints.NO_ADMIN : adminServer.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
        if (adminServer != null) {
            adminServer.stop(0);
            adminPool.shutdownNow();
        }
    }

    // -------------------------------------------------------------- handlers

    /**
     * What the transfer port answers: only what a client actually acts on.
     *
     * <p>Deliberately not the operator's view. Everything else in the full document is either
     * something the client already learns from the blueprint (the block size parameters, the
     * decomposition limit), something the response headers state per response and more
     * authoritatively (the transport encoding), or plain operational data about how much the
     * server stores -- which is nobody else's business.
     */
    private void handleClientConfig(HttpExchange e) throws IOException {
        ObjectNode n = json.createObjectNode();
        n.put("hashAlgorithm", Hashes.ALGORITHM);
        n.put("maxBlockSize", maxBlockSize);
        sendJson(e, 200, n);
    }

    /** The operator's view, on the admin port: everything about this server's configuration. */
    private void handleAdminConfig(HttpExchange e) throws IOException {
        Chunker.Params p = store.chunkParams();
        ObjectNode n = json.createObjectNode();
        n.put("hashAlgorithm", Hashes.ALGORITHM);
        n.put("maxBlockSize", maxBlockSize);
        n.put("blockSizeMin", p.min());
        n.put("blockSizeAvg", p.avg());
        n.put("blockSizeMax", p.max());
        n.put("storedBlocks", store.blocks().chunkCount());
        n.put("storedBlockBytes", store.blocks().storedBytes());
        n.put("maxSevenZDecomposeSize", store.limits().maxSevenZBytes());
        n.put("transportCompression", compressionLevel > 0 ? Wire.ENCODING_GZIP : "none");
        n.put("transportCompressionLevel", compressionLevel);
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
        boolean gzip = useGzip(e);
        e.getResponseHeaders().add("Content-Type", Wire.CONTENT_TYPE_BLOCKS);
        e.getResponseHeaders().add("X-JDT-Max-Block-Size", Integer.toString(maxBlockSize));
        if (gzip) {
            e.getResponseHeaders().add("Content-Encoding", Wire.ENCODING_GZIP);
        }
        e.sendResponseHeaders(200, 0);
        long sent;
        try (OutputStream raw = e.getResponseBody();
             OutputStream body = wrapForTransport(raw, gzip);
             Wire.BlockWriter writer = new Wire.BlockWriter(body, maxBlockSize)) {
            for (Hash h : wanted) {
                if (!blocks.contains(h)) {
                    // The blueprint the client holds and the store disagree; abort loudly.
                    throw new IOException("client asked for unknown block " + h);
                }
                writer.write(h, blocks.get(h));
            }
            sent = writer.bytes();
        }
        log.info("served " + wanted.size() + " blocks (" + VersionStore.human(sent)
                + (gzip ? ", gzip" : ", uncompressed") + ") of " + info.id());
    }

    /** Compression happens only when the client asks for it and the server was not told to skip it. */
    private boolean useGzip(HttpExchange e) {
        return compressionLevel > 0
                && Wire.acceptsGzip(e.getRequestHeaders().getFirst("Accept-Encoding"));
    }

    private OutputStream wrapForTransport(OutputStream raw, boolean gzip) throws IOException {
        OutputStream buffered = new java.io.BufferedOutputStream(raw, 1 << 16);
        return gzip ? Wire.gzip(buffered, compressionLevel) : buffered;
    }

    /** Streams the full archive as framed blocks, cut by the same content defined chunker. */
    private void handleFull(HttpExchange e, VersionStore.VersionInfo info) throws IOException {
        Path file = store.archiveFile(info.id());
        boolean gzip = useGzip(e);
        e.getResponseHeaders().add("Content-Type", Wire.CONTENT_TYPE_BLOCKS);
        e.getResponseHeaders().add("X-JDT-Archive-SHA256", info.sha256());
        e.getResponseHeaders().add("X-JDT-Archive-Size", Long.toString(info.size()));
        e.getResponseHeaders().add("X-JDT-Max-Block-Size", Integer.toString(maxBlockSize));
        if (gzip) {
            e.getResponseHeaders().add("Content-Encoding", Wire.ENCODING_GZIP);
        }
        e.sendResponseHeaders(200, 0);

        Chunker chunker = new Chunker(store.chunkParams());
        try (OutputStream raw = e.getResponseBody();
             OutputStream body = wrapForTransport(raw, gzip);
             Wire.BlockWriter writer = new Wire.BlockWriter(body, maxBlockSize);
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
        sb.append("<p><strong>This is the admin port.</strong> It serves this page and ")
                .append("<code>POST /api/rescan</code> on top of the read only API. Clients use the ")
                .append("transfer port (").append(port()).append("), which carries neither. Keep ")
                .append("this port off the network.</p>");
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
        sb.append("<p>Ingest archives dropped into the archive directory: "
                + "<code>curl -X POST ").append(scheme).append("://localhost:").append(adminPort())
                .append("/api/rescan</code></p>");
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
