package com.bw.jdt.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bw.jdt.core.Blueprint;
import com.bw.jdt.core.BlueprintCodec;
import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ChunkSource;
import com.bw.jdt.core.ChunkStore;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.DecomposeLimits;
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
    private boolean keepBaseCache;
    private int indexThreads = Math.min(8, Runtime.getRuntime().availableProcessors());
    private int rebuildThreads = Math.min(8, Runtime.getRuntime().availableProcessors());

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

    /** Nested archives rebuilt in parallel. This is the dominant cost of a warm transfer. */
    public DeltaClient rebuildThreads(int threads) {
        this.rebuildThreads = Math.max(1, threads);
        return this;
    }

    /** Entries of the base archive decomposed in parallel while indexing it. */
    public DeltaClient indexThreads(int threads) {
        this.indexThreads = Math.max(1, threads);
        return this;
    }

    /**
     * Copy the index over to the new version instead of renaming it, keeping the base indexed
     * as well. Costs a full copy of the cache; only needed when several bases stay in use.
     */
    public DeltaClient keepBaseCache(boolean keep) {
        this.keepBaseCache = keep;
        return this;
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
            boolean delta,
            Phases phases) {

        public long transferredBytes() {
            return blueprintBytes + blockBytes;
        }

        public double savedFraction() {
            return archiveSize == 0 ? 0 : 1.0 - (double) transferredBytes() / archiveSize;
        }
    }

    /**
     * Wall clock of the three phases of a delta transfer, in nanoseconds. Worth reporting
     * because which one dominates decides what is worth optimising: indexing is pure local CPU,
     * download scales with the link, and rebuild is CPU again.
     */
    public record Phases(long indexNanos, long downloadNanos, long rebuildNanos) {
        public static final Phases NONE = new Phases(0, 0, 0);

        public String describe() {
            return "index " + Fmt.seconds(indexNanos)
                    + ", download " + Fmt.seconds(downloadNanos)
                    + ", rebuild " + Fmt.seconds(rebuildNanos);
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

        long tFull = System.nanoTime();
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
        return new TransferResult(id, size, actual, 0, received, blocks[0], 0, false,
                new Phases(0, System.nanoTime() - tFull, 0));
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

        Path localStoreDir = localStoreFor(baseArchive, bp.chunkParams(), bp.limits());
        Path incomingDir = localStoreDir.resolveSibling(localStoreDir.getFileName() + "-incoming-" + id);
        boolean handOver = false;
        TransferResult result;
        try (ChunkStore local = ChunkStore.open(localStoreDir);
             ChunkStore incoming = ChunkStore.open(incomingDir)) {
            long indexNanos = 0;
            if (local.chunkCount() == 0) {
                log.info("indexing local base " + baseArchive.getFileName() + " ...");
                long t0 = System.nanoTime();
                try (WorkDir wd = WorkDir.createTemp(localStoreDir.resolve("tmp"), "base-")) {
                    // Same block parameters and the same decomposition policy the server used
                    // for the target version, otherwise the two sides would cut different blocks
                    // out of identical content and nothing would be reusable.
                    new Decomposer(local, bp.chunkParams(), bp.limits())
                            .withThreads(indexThreads)
                            .decompose(ByteSource.ofFile(baseArchive), wd);
                }
                local.sync();
                indexNanos = System.nanoTime() - t0;
                log.info("indexed " + local.chunkCount() + " local blocks in "
                        + Fmt.seconds(indexNanos));
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

            long tDownload = System.nanoTime();
            long blockBytes = missing.isEmpty() ? 0 : downloadBlocks(id, missing, maxBlock, incoming);
            incoming.sync();
            long downloadNanos = System.nanoTime() - tDownload;

            ChunkSource source = ChunkSource.composite(List.of(local, incoming));
            long tRebuild = System.nanoTime();
            Path tmp = tempNextTo(out);
            try (WorkDir wd = WorkDir.createTemp(incomingDir.resolve("tmp"), "rebuild-");
                 OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
                Hash actual = new Reassembler(source, wd)
                        .withThreads(rebuildThreads)
                        .writeTo(bp, os);
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
            long rebuildNanos = System.nanoTime() - tRebuild;

            // Fold the downloaded blocks into the base index so the two together describe the
            // version just built. The directory is relabelled below, once both stores are shut.
            local.importFrom(incoming);
            handOver = true;

            Phases phases = new Phases(indexNanos, downloadNanos, rebuildNanos);
            log.info("phases: " + phases.describe());
            result = new TransferResult(id, size, expected, bpBytes.length, blockBytes,
                    missing.size(), reused, true, phases);
        } finally {
            deleteRecursively(incomingDir);
        }
        // Both stores are closed now, so the directory can be renamed.
        if (handOver) {
            handOverCache(localStoreDir, expected, bp.chunkParams(), bp.limits());
        }
        return result;
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

    /**
     * Cache location for the decomposed local base. Keyed by the base's content hash and by
     * every parameter that changes which blocks come out of it, so a cache built under one
     * policy is never reused under another.
     */
    private Path localStoreFor(Path baseArchive, Chunker.Params params, DecomposeLimits limits)
            throws IOException {
        Path dir = cacheDir.resolve(cacheKey(Hashes.ofFile(baseArchive), params, limits));
        Files.createDirectories(dir);
        return dir;
    }

    private static String cacheKey(Hash archiveHash, Chunker.Params params, DecomposeLimits limits) {
        return archiveHash.hex().substring(0, 24) + "-" + params.avg() + "-" + params.max()
                + "-7z" + limits.maxSevenZBytes();
    }

    /**
     * Relabels the base's index as the index of the version just rebuilt.
     *
     * <p>After a successful transfer the base index plus the downloaded blocks together already
     * cover every block of the new version, so the next run -- a fresh process upgrading from
     * exactly this file -- can skip decomposing it, which is otherwise close to half of the
     * total time. Renaming is essentially free compared to re-indexing.
     *
     * <p>The base's own index is consumed in the process. That is the right trade for a client
     * that upgrades forward one version at a time; pass {@code --keep-base-cache} to copy
     * instead of rename when several bases have to stay indexed.
     */
    private void handOverCache(Path localStoreDir, Hash newVersionHash,
                               Chunker.Params params, DecomposeLimits limits) {
        Path target = cacheDir.resolve(cacheKey(newVersionHash, params, limits));
        if (target.equals(localStoreDir)) {
            return;
        }
        try {
            if (Files.exists(target)) {
                // The next version is already indexed; nothing to hand over.
                return;
            }
            if (keepBaseCache) {
                copyDirectory(localStoreDir, target);
                log.info("indexed the new version into " + target.getFileName() + " (base kept)");
            } else {
                Files.move(localStoreDir, target);
                log.info("cache handed over to " + target.getFileName()
                        + "; the next upgrade from this file skips indexing");
            }
        } catch (IOException e) {
            // Purely an optimisation: the next run just indexes again.
            log.info("could not hand the cache over (" + e.getMessage() + "), next run will re-index");
        }
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var s = Files.list(from)) {
            for (Path p : s.toList()) {
                if (Files.isRegularFile(p)) {
                    Files.copy(p, to.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
