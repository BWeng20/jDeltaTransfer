package com.bw.jdt.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bw.jdt.core.Blueprint;
import com.bw.jdt.core.BlueprintCodec;
import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ChunkSource;
import com.bw.jdt.core.ChunkStore;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Decomposer;
import com.bw.jdt.core.Fmt;
import com.bw.jdt.core.Hash;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.core.Reassembler;
import com.bw.jdt.core.WorkDir;
import com.bw.jdt.proto.Wire;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/** Client side of the protocol: talks to the server and rebuilds archives locally. */
public final class DeltaClient implements Closeable {

    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Path cacheDir;
    private final Log log;

    public interface Log {
        void info(String message);

        Log STDOUT = message -> System.out.println("[client] " + message);
    }

    public DeltaClient(URI base, Path cacheDir, Log log) {
        this.base = base;
        this.cacheDir = cacheDir;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Outcome of a transfer, so callers can report the delta efficiency. */
    public record TransferResult(
            String versionId,
            long archiveSize,
            Hash archiveHash,
            long blueprintBytes,
            long blockBytes,
            long blocksRequested,
            long blocksReused,
            boolean delta) {

        public long transferredBytes() {
            return blueprintBytes + blockBytes;
        }

        public double savedFraction() {
            return archiveSize == 0 ? 0 : 1.0 - (double) transferredBytes() / archiveSize;
        }
    }

    // -------------------------------------------------------------- metadata

    public JsonNode listVersions() throws IOException, InterruptedException {
        return json.readTree(getBytes("/api/versions"));
    }

    public JsonNode config() throws IOException, InterruptedException {
        return json.readTree(getBytes("/api/config"));
    }

    public JsonNode versionInfo(String id) throws IOException, InterruptedException {
        return json.readTree(getBytes("/api/versions/" + id));
    }

    public Blueprint blueprint(String id) throws IOException, InterruptedException {
        return BlueprintCodec.fromGzipBytes(getBytes("/api/versions/" + id + "/blueprint"));
    }

    // -------------------------------------------------------------- transfer

    /** Downloads the complete archive as framed blocks and verifies the result. */
    public TransferResult fetchFull(String id, Path out) throws IOException, InterruptedException {
        JsonNode info = versionInfo(id);
        Hash expected = Hash.parse(info.path("sha256").asText());
        long size = info.path("size").asLong();
        int maxBlock = config().path("maxBlockSize").asInt();

        Path tmp = tempNextTo(out);
        long[] blocks = {0};
        long received;
        HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(base.resolve("/api/versions/" + id + "/full"))
                .GET().build());
        try (InputStream in = resp.body();
             OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            requireOk(resp);
            received = Wire.readBlocks(in, maxBlock, (hash, payload) -> {
                os.write(payload);
                blocks[0]++;
            });
        }
        Hash actual = Hashes.ofFile(tmp);
        if (!actual.equals(expected)) {
            Files.deleteIfExists(tmp);
            throw new IOException("full download hash mismatch: expected " + expected + ", got " + actual);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return new TransferResult(id, size, actual, 0, received, blocks[0], 0, false);
    }

    /**
     * Downloads only the blocks that are not already derivable from {@code baseArchive}.
     *
     * <p>The local older version is decomposed with the same recursive rules and the same block
     * parameters the server used, which is what lets a block produced inside a nested CAB on
     * the server match a block produced inside that CAB here.
     */
    public TransferResult fetchDelta(String id, Path baseArchive, Path out)
            throws IOException, InterruptedException {
        JsonNode info = versionInfo(id);
        Hash expected = Hash.parse(info.path("sha256").asText());
        long size = info.path("size").asLong();
        int maxBlock = config().path("maxBlockSize").asInt();

        byte[] bpBytes = getBytes("/api/versions/" + id + "/blueprint");
        Blueprint bp = BlueprintCodec.fromGzipBytes(bpBytes);
        if (!bp.archiveHash().equals(expected)) {
            throw new IOException("blueprint does not describe the announced archive");
        }
        log.info("blueprint " + Fmt.human(bpBytes.length) + ", "
                + bp.stats().distinctChunks() + " distinct blocks needed");

        Path localStoreDir = localStoreFor(baseArchive, bp.chunkParams());
        Path incomingDir = localStoreDir.resolveSibling(localStoreDir.getFileName() + "-incoming-" + id);
        try (ChunkStore local = ChunkStore.open(localStoreDir);
             ChunkStore incoming = ChunkStore.open(incomingDir)) {
            if (local.chunkCount() == 0) {
                log.info("indexing local base " + baseArchive.getFileName() + " ...");
                long t0 = System.nanoTime();
                try (WorkDir wd = WorkDir.createTemp(localStoreDir.resolve("tmp"), "base-")) {
                    new Decomposer(local, bp.chunkParams())
                            .decompose(ByteSource.ofFile(baseArchive), wd);
                }
                local.sync();
                log.info("indexed " + local.chunkCount() + " local blocks in "
                        + (System.nanoTime() - t0) / 1_000_000_000 + "s");
            } else {
                log.info("reusing cached index of the local base: " + local.chunkCount() + " blocks");
            }

            // Blocks pulled from the server go into their own store, so "reused locally" stays
            // an honest measure of what the old version contributed.
            List<Hash> needed = new java.util.ArrayList<>(bp.distinctChunks().keySet());
            List<Hash> missing = new java.util.ArrayList<>();
            for (Hash h : needed) {
                if (!local.contains(h) && !incoming.contains(h)) {
                    missing.add(h);
                }
            }
            long reused = needed.size() - missing.size();
            log.info("need " + missing.size() + " of " + needed.size()
                    + " blocks from the server (" + reused + " reused locally)");

            long blockBytes = missing.isEmpty() ? 0 : downloadBlocks(id, missing, maxBlock, incoming);
            incoming.sync();

            ChunkSource source = ChunkSource.composite(List.of(local, incoming));
            Path tmp = tempNextTo(out);
            try (WorkDir wd = WorkDir.createTemp(incomingDir.resolve("tmp"), "rebuild-");
                 OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
                Hash actual = new Reassembler(source, wd).writeTo(bp, os);
                os.flush();
                if (!actual.equals(expected)) {
                    throw new IOException("rebuilt archive hash mismatch: expected " + expected
                            + ", got " + actual);
                }
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);

            return new TransferResult(id, size, expected, bpBytes.length, blockBytes,
                    missing.size(), reused, true);
        } finally {
            deleteRecursively(incomingDir);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // Leftover scratch data is harmless; the next run overwrites it.
        }
    }

    private long downloadBlocks(String id, List<Hash> missing, int maxBlock, ChunkStore into)
            throws IOException, InterruptedException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(missing.size() * 32 + 16);
        Wire.writeHashList(missing, body);
        HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(
                        base.resolve("/api/versions/" + id + "/blocks"))
                .header("Content-Type", Wire.CONTENT_TYPE_HASHES)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build());
        try (InputStream in = resp.body()) {
            requireOk(resp);
            return Wire.readBlocks(in, maxBlock, into::put);
        }
    }

    // --------------------------------------------------------------- helpers

    /** Cache location for the decomposed local base, keyed by its content hash. */
    private Path localStoreFor(Path baseArchive, Chunker.Params params) throws IOException {
        Hash baseHash = Hashes.ofFile(baseArchive);
        String key = baseHash.hex().substring(0, 24) + "-" + params.avg() + "-" + params.max();
        Path dir = cacheDir.resolve(key);
        Files.createDirectories(dir);
        return dir;
    }

    private byte[] getBytes(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("GET " + path + " -> HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8).trim());
        }
        return resp.body();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void requireOk(HttpResponse<InputStream> resp) throws IOException {
        if (resp.statusCode() != 200) {
            throw new IOException(resp.uri() + " -> HTTP " + resp.statusCode());
        }
    }

    private static Path tempNextTo(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return parent.resolve(out.getFileName() + ".part");
    }

    @Override
    public void close() {
        http.close();
    }
}
