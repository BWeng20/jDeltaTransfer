package com.bw.jdt.core;

import com.bw.jdt.core.format.ArchiveSniffer;
import com.bw.jdt.core.format.ContainerCodec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turns an archive into a {@link Blueprint} and fills a {@link ChunkStore} with the blocks it
 * references.
 *
 * <p>Nested containers are opened recursively, so the blocks describe <em>decompressed</em>
 * payloads. That is the whole point: two versions of an archive that differ in one file inside
 * a nested CAB share every block except the handful covering that file, even though their
 * compressed bytes have almost nothing in common.
 */
public final class Decomposer {

    /** How deep to look into nested containers. Outer ZIP plus nested archives plus slack. */
    public static final int DEFAULT_MAX_DEPTH = 6;

    private final ChunkStore store;
    private final Chunker chunker;
    private final DecomposeLimits limits;
    private final int maxDepth;
    private volatile int threads = 1;
    private final Listener listener;

    /** Callbacks may arrive from several threads at once; implementations must cope. */
    public interface Listener {
        void onContainer(ContainerFormat format, int depth, long size);

        void onOpaque(ContainerFormat attempted, int depth, long size);

        Listener NOOP = new Listener() {
            @Override
            public void onContainer(ContainerFormat format, int depth, long size) {
            }

            @Override
            public void onOpaque(ContainerFormat attempted, int depth, long size) {
            }
        };
    }

    public Decomposer(ChunkStore store, Chunker.Params params) {
        this(store, params, DecomposeLimits.DEFAULT, DEFAULT_MAX_DEPTH, Listener.NOOP);
    }

    public Decomposer(ChunkStore store, Chunker.Params params, DecomposeLimits limits) {
        this(store, params, limits, DEFAULT_MAX_DEPTH, Listener.NOOP);
    }

    public Decomposer(ChunkStore store, Chunker.Params params, DecomposeLimits limits,
                      int maxDepth, Listener listener) {
        this.store = store;
        this.chunker = new Chunker(params);
        this.limits = limits;
        this.maxDepth = maxDepth;
        this.listener = listener;
    }

    /**
     * How many entries of the outermost archive may be decomposed at once. Worth raising on a
     * client, where indexing the local base is on the critical path of every cold start.
     */
    public Decomposer withThreads(int threads) {
        this.threads = Math.max(1, threads);
        return this;
    }

    public Blueprint decompose(ByteSource archive, WorkDir wd) throws IOException {
        Hash archiveHash = Hashes.of(archive);
        Node root = build(archive, 0, wd);
        return new Blueprint(archiveHash, archive.size(), chunker.params(), limits, root);
    }

    private Node build(ByteSource src, int depth, WorkDir wd) throws IOException {
        if (depth < maxDepth && src.size() >= ArchiveSniffer.MIN_CONTAINER_SIZE) {
            ContainerFormat fmt = ArchiveSniffer.detect(src);
            if (fmt != null) {
                ContainerCodec codec = Codecs.of(fmt);
                ContainerCodec.Decomposition d = codec.decompose(src, wd, limits);
                if (d != null) {
                    listener.onContainer(fmt, depth, src.size());
                    return new Node.Container(fmt, d.meta(),
                            buildChildren(d.parts(), depth + 1, wd), src.size());
                }
                listener.onOpaque(fmt, depth, src.size());
            }
        }
        return blob(src);
    }

    /**
     * Decomposes the entries of one container.
     *
     * <p>Only the entries of the outermost archive are handled in parallel. Going parallel at
     * every level would need a work stealing pool to avoid a task waiting on subtasks that the
     * same fixed pool is not free to run; one level already covers the expensive work, because
     * that is where the nested archives sit.
     */
    private List<Node> buildChildren(List<ByteSource> parts, int depth, WorkDir wd) throws IOException {
        if (threads <= 1 || depth != 1 || parts.size() < 2) {
            List<Node> children = new ArrayList<>(parts.size());
            for (ByteSource part : parts) {
                children.add(build(part, depth, wd));
            }
            return children;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, parts.size()));
        try {
            List<Future<Node>> futures = new ArrayList<>(parts.size());
            for (ByteSource part : parts) {
                futures.add(pool.submit(() -> build(part, depth, wd)));
            }
            List<Node> children = new ArrayList<>(parts.size());
            for (Future<Node> f : futures) {
                children.add(awaitNode(f));
            }
            return children;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Node awaitNode(Future<Node> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("decomposition interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("decomposition failed", cause);
        }
    }

    /** Splits a payload into content defined blocks and stores every new one. */
    private Node.Blob blob(ByteSource src) throws IOException {
        List<ChunkRef> refs = new ArrayList<>();
        try (InputStream in = src.openStream()) {
            chunker.split(in, (buf, off, len) -> {
                Hash h = Hashes.of(buf, off, len);
                store.put(h, buf, off, len);
                refs.add(new ChunkRef(h, len));
            });
        }
        return new Node.Blob(src.size(), refs);
    }
}
