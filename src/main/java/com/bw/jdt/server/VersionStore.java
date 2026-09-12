package com.bw.jdt.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bw.jdt.core.Blueprint;
import com.bw.jdt.core.BlueprintCodec;
import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ChunkStore;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.ContainerFormat;
import com.bw.jdt.core.DecomposeLimits;
import com.bw.jdt.core.Decomposer;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.core.Reassembler;
import com.bw.jdt.core.format.ArchiveSniffer;
import com.bw.jdt.core.WorkDir;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Persistent state of the server: the archive versions, their hashes and their blueprints.
 *
 * <p>Hashes are written to {@code index.json} with an atomic replace, so a restart never loses
 * them and never observes a half written index. The blocks of every version live in one shared
 * {@link ChunkStore}, which is what makes the deltas between versions cheap on disk as well.
 */
public final class VersionStore implements Closeable {

    public static final int INDEX_FORMAT_VERSION = 1;

    private final Path storeDir;
    private final Path archiveDir;
    private final Path blueprintDir;
    private final ChunkStore blocks;
    private final Chunker.Params chunkParams;
    private final boolean verifyOnIngest;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, VersionInfo> versions = new LinkedHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile int ingestThreads = 1;
    private volatile DecomposeLimits limits = DecomposeLimits.DEFAULT;

    /** Everything the HTTP API publishes about one archive version. */
    public record VersionInfo(
            String id,
            String fileName,
            long size,
            String sha256,
            String blueprintSha256,
            long blueprintBytes,
            long blockRefs,
            long distinctBlocks,
            long containers,
            long opaqueContainers,
            String ingestedAt) {
    }

    private VersionStore(Path storeDir, Path archiveDir, ChunkStore blocks,
                         Chunker.Params chunkParams, boolean verifyOnIngest) {
        this.storeDir = storeDir;
        this.archiveDir = archiveDir;
        this.blueprintDir = storeDir.resolve("blueprints");
        this.blocks = blocks;
        this.chunkParams = chunkParams;
        this.verifyOnIngest = verifyOnIngest;
    }

    public static VersionStore open(Path storeDir, Path archiveDir, Chunker.Params params,
                                    boolean verifyOnIngest, boolean forceReindex) throws IOException {
        Files.createDirectories(storeDir);
        Files.createDirectories(archiveDir);
        Files.createDirectories(storeDir.resolve("blueprints"));

        Path indexFile = storeDir.resolve("index.json");
        Chunker.Params effective = params;
        if (Files.exists(indexFile) && !forceReindex) {
            ObjectMapper m = new ObjectMapper();
            ObjectNode root = (ObjectNode) m.readTree(Files.readAllBytes(indexFile));
            Chunker.Params stored = new Chunker.Params(
                    root.path("chunkMin").asInt(), root.path("chunkAvg").asInt(), root.path("chunkMax").asInt());
            if (!stored.equals(params)) {
                // Block boundaries depend on these; mixing them would silently kill dedup.
                throw new IOException("store was built with block parameters min=" + stored.min()
                        + " avg=" + stored.avg() + " max=" + stored.max()
                        + " but the server was started with min=" + params.min() + " avg=" + params.avg()
                        + " max=" + params.max() + ". Restart with the old values or use --force-reindex.");
            }
            effective = stored;
        }

        if (forceReindex) {
            deleteQuietly(storeDir.resolve("blocks"));
            deleteQuietly(indexFile);
            try (Stream<Path> bps = Files.list(storeDir.resolve("blueprints"))) {
                for (Path p : bps.toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }

        ChunkStore blocks = ChunkStore.open(storeDir.resolve("blocks"));
        VersionStore store = new VersionStore(storeDir, archiveDir, blocks, effective, verifyOnIngest);
        store.loadIndex();
        // Record the block parameters right away, so a restart with different limits is caught
        // even if no version has been ingested yet.
        store.saveIndex();
        return store;
    }

    // ------------------------------------------------------------------ read

    /** How far decomposition goes; travels to the client inside every blueprint. */
    public void setLimits(DecomposeLimits limits) {
        this.limits = limits;
    }

    public DecomposeLimits limits() {
        return limits;
    }

    /** Spare cores for the verify rebuild, given that scan already runs archives in parallel. */
    private int rebuildThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(1, ingestThreads));
    }

    /** How many archives may be decomposed at once during a scan. */
    public void setIngestThreads(int threads) {
        this.ingestThreads = Math.max(1, threads);
    }

    public Chunker.Params chunkParams() {
        return chunkParams;
    }

    public ChunkStore blocks() {
        return blocks;
    }

    public Path archiveDir() {
        return archiveDir;
    }

    public synchronized List<VersionInfo> versions() {
        List<VersionInfo> list = new ArrayList<>(versions.values());
        list.sort(Comparator.comparing(VersionInfo::id));
        return list;
    }

    public synchronized VersionInfo version(String id) {
        return versions.get(id);
    }

    public Path archiveFile(String id) {
        VersionInfo info = version(id);
        return info == null ? null : archiveDir.resolve(info.fileName());
    }

    public byte[] blueprintBytes(String id) throws IOException {
        Path p = blueprintDir.resolve(id + ".bp");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    public Blueprint blueprint(String id) throws IOException {
        byte[] raw = blueprintBytes(id);
        return raw == null ? null : BlueprintCodec.fromGzipBytes(raw);
    }

    // ---------------------------------------------------------------- ingest

    /** Ingests every archive in the archive directory that is not indexed yet. */
    public int scan(Log log) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(archiveDir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(VersionStore::notTransient)
                    .sorted()
                    .toList();
        }

        // Any supported container may sit at the top level, and the format is decided by the
        // magic bytes rather than by the extension -- the same rule the decomposer follows all
        // the way down. A file named .dat or .pak that happens to be a cabinet is ingested too.
        List<Path> candidates = new ArrayList<>();
        Map<String, Path> byId = new LinkedHashMap<>();
        for (Path file : files) {
            ContainerFormat fmt = ArchiveSniffer.detect(ByteSource.ofFile(file));
            if (fmt == null) {
                continue;
            }
            String id = idFor(file);
            Path clash = byId.putIfAbsent(id, file);
            if (clash != null) {
                log.warn("skipping " + file.getFileName() + ": its version id '" + id
                        + "' is already taken by " + clash.getFileName()
                        + ". Rename one of them to serve both.");
                continue;
            }
            candidates.add(file);
        }

        List<Path> pending = new ArrayList<>();
        for (Path archive : candidates) {
            String id = idFor(archive);
            VersionInfo existing = version(id);
            if (existing != null && existing.size() == Files.size(archive)
                    && Files.exists(blueprintDir.resolve(id + ".bp"))) {
                continue;
            }
            pending.add(archive);
        }
        if (pending.isEmpty()) {
            return 0;
        }
        if (ingestThreads <= 1 || pending.size() == 1) {
            for (Path archive : pending) {
                ingest(idFor(archive), archive, log);
            }
            return pending.size();
        }

        // Decomposition is CPU bound and single threaded per archive, so ingesting several at
        // once is a large win on the first run over a whole version series.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(ingestThreads, pending.size()));
        List<Future<?>> futures = new ArrayList<>();
        for (Path archive : pending) {
            futures.add(pool.submit(() -> ingest(idFor(archive), archive, log)));
        }
        pool.shutdown();
        IOException failure = null;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("ingest failed: " + cause);
                if (failure == null) {
                    failure = new IOException("ingest failed", cause);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return pending.size();
    }

    /** Decomposes one archive, stores its blocks and records its hashes. */
    public VersionInfo ingest(String id, Path archive, Log log) throws IOException {
        {
            long t0 = System.nanoTime();
            ByteSource src = ByteSource.ofFile(archive);

            java.util.concurrent.atomic.AtomicLong containers = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong opaque = new java.util.concurrent.atomic.AtomicLong();
            Decomposer.Listener listener = new Decomposer.Listener() {
                @Override
                public void onContainer(ContainerFormat format, int depth, long size) {
                    containers.incrementAndGet();
                }

                @Override
                public void onOpaque(ContainerFormat attempted, int depth, long size) {
                    opaque.incrementAndGet();
                    log.warn("  " + attempted + " at depth " + depth + " (" + size
                            + " bytes) cannot be rebuilt exactly, kept as opaque blocks");
                }
            };

            Blueprint bp;
            try (WorkDir wd = WorkDir.createTemp(storeDir.resolve("tmp"), "ingest-")) {
                Decomposer decomposer = new Decomposer(blocks, chunkParams, limits,
                        Decomposer.DEFAULT_MAX_DEPTH, listener);
                bp = decomposer.decompose(src, wd);
                if (verifyOnIngest) {
                    try (WorkDir vwd = WorkDir.createTemp(storeDir.resolve("tmp"), "verify-")) {
                        new Reassembler(blocks, vwd)
                                .withThreads(rebuildThreads())
                                .writeVerified(bp, OutputStream.nullOutputStream());
                    }
                }
            }
            blocks.sync();

            byte[] bpBytes = BlueprintCodec.toGzipBytes(bp);
            Path bpFile = blueprintDir.resolve(id + ".bp");
            atomicWrite(bpFile, bpBytes);

            Blueprint.Stats stats = bp.stats();
            VersionInfo info = new VersionInfo(
                    id,
                    archive.getFileName().toString(),
                    bp.archiveSize(),
                    bp.archiveHash().hex(),
                    Hashes.of(bpBytes).hex(),
                    bpBytes.length,
                    stats.chunkRefs(),
                    stats.distinctChunks(),
                    containers.get(),
                    opaque.get(),
                    Instant.now().toString());

            // Only the index update is serialised; decomposition above runs concurrently.
            writeLock.lock();
            try {
                synchronized (this) {
                    versions.put(id, info);
                }
                saveIndex();
            } finally {
                writeLock.unlock();
            }

            long ms = (System.nanoTime() - t0) / 1_000_000;
            // With parallel ingest the store also grows from other archives, so report the
            // absolute store size rather than a per-version delta that would be misleading.
            log.info(String.format(Locale.ROOT,
                    "ingested %s: %s, sha256=%s, %d blocks (%d distinct), %d containers, "
                            + "block store now %s, %.1fs",
                    id, human(info.size()), info.sha256().substring(0, 16),
                    stats.chunkRefs(), stats.distinctChunks(), containers.get(),
                    human(blocks.storedBytes()), ms / 1000.0));
            return info;
        }
    }

    // ----------------------------------------------------------- persistence

    private void loadIndex() throws IOException {
        Path indexFile = storeDir.resolve("index.json");
        if (!Files.exists(indexFile)) {
            return;
        }
        ObjectNode root = (ObjectNode) json.readTree(Files.readAllBytes(indexFile));
        int fmt = root.path("formatVersion").asInt();
        if (fmt != INDEX_FORMAT_VERSION) {
            throw new IOException("unsupported index format version " + fmt);
        }
        ArrayNode arr = (ArrayNode) root.path("versions");
        synchronized (this) {
            versions.clear();
            for (var n : arr) {
                VersionInfo info = new VersionInfo(
                        n.path("id").asText(),
                        n.path("fileName").asText(),
                        n.path("size").asLong(),
                        n.path("sha256").asText(),
                        n.path("blueprintSha256").asText(),
                        n.path("blueprintBytes").asLong(),
                        n.path("blockRefs").asLong(),
                        n.path("distinctBlocks").asLong(),
                        n.path("containers").asLong(),
                        n.path("opaqueContainers").asLong(),
                        n.path("ingestedAt").asText());
                versions.put(info.id(), info);
            }
        }
    }

    private void saveIndex() throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("formatVersion", INDEX_FORMAT_VERSION);
        root.put("hashAlgorithm", Hashes.ALGORITHM);
        root.put("chunkMin", chunkParams.min());
        root.put("chunkAvg", chunkParams.avg());
        root.put("chunkMax", chunkParams.max());
        ArrayNode arr = root.putArray("versions");
        for (VersionInfo v : versions()) {
            ObjectNode n = arr.addObject();
            n.put("id", v.id());
            n.put("fileName", v.fileName());
            n.put("size", v.size());
            n.put("sha256", v.sha256());
            n.put("blueprintSha256", v.blueprintSha256());
            n.put("blueprintBytes", v.blueprintBytes());
            n.put("blockRefs", v.blockRefs());
            n.put("distinctBlocks", v.distinctBlocks());
            n.put("containers", v.containers());
            n.put("opaqueContainers", v.opaqueContainers());
            n.put("ingestedAt", v.ingestedAt());
        }
        atomicWrite(storeDir.resolve("index.json"), json.writeValueAsBytes(root));
    }

    private static void atomicWrite(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> s = Files.walk(path)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    /**
     * Version id: the file name without its extension.
     *
     * <p>Since any supported format may be the outermost container, two files can map to the
     * same id ({@code app.zip} and {@code app.7z}). {@link #scan} detects that and skips the
     * second rather than letting one silently replace the other in the index.
     */
    private static String idFor(Path archive) {
        String name = archive.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Skips half written files, so a scan cannot pick up an archive that is still being copied. */
    private static boolean notTransient(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return !n.startsWith(".") && !n.endsWith(".part") && !n.endsWith(".tmp")
                && !n.endsWith(".crdownload");
    }

    public static String human(long bytes) {
        return com.bw.jdt.core.Fmt.human(bytes);
    }

    @Override
    public void close() throws IOException {
        blocks.close();
    }

    /** Minimal logging hook so the store does not depend on a logging framework. */
    public interface Log {
        void info(String message);

        void warn(String message);

        Log STDOUT = new Log() {
            @Override
            public void info(String message) {
                System.out.println("[store] " + message);
            }

            @Override
            public void warn(String message) {
                System.out.println("[store] WARN " + message);
            }
        };
    }
}
