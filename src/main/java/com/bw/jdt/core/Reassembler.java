package com.bw.jdt.core;

import com.bw.jdt.core.format.ContainerCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Rebuilds an archive from a {@link Blueprint} plus the blocks it references. */
public final class Reassembler {

    private final ChunkSource chunks;
    private final WorkDir wd;
    private volatile int threads = 1;

    public Reassembler(ChunkSource chunks, WorkDir wd) {
        this.chunks = chunks;
        this.wd = wd;
    }

    /**
     * How many nested archives of the outermost container may be rebuilt at once.
     *
     * <p>Re-encoding a nested container is the expensive part of reassembly -- LZMA2 for 7z,
     * MSZIP for CAB, deflate for ZIP -- and the nested archives are independent subtrees, so
     * they parallelise cleanly. Leaf payloads are not affected: they stream straight out of the
     * block store and never touch a temp file.
     */
    public Reassembler withThreads(int threads) {
        this.threads = Math.max(1, threads);
        return this;
    }

    /** Writes the archive and returns its SHA-256, so the caller can verify without re-reading. */
    public Hash writeTo(Blueprint bp, OutputStream out) throws IOException {
        DigestOutputStream digest = new DigestOutputStream(out, Hashes.newDigest());
        write(bp.root(), digest);
        digest.flush();
        return Hash.wrap(digest.getMessageDigest().digest());
    }

    /** Rebuilds and fails unless the result matches the hash recorded in the blueprint. */
    public void writeVerified(Blueprint bp, OutputStream out) throws IOException {
        Hash actual = writeTo(bp, out);
        if (!actual.equals(bp.archiveHash())) {
            throw new IOException("rebuilt archive hash mismatch: expected "
                    + bp.archiveHash() + " but got " + actual);
        }
    }

    public void write(Node node, OutputStream out) throws IOException {
        write(node, out, 0);
    }

    private void write(Node node, OutputStream out, int depth) throws IOException {
        if (node instanceof Node.Blob b) {
            for (ChunkRef ref : b.chunks()) {
                out.write(chunks.get(ref.hash()));
            }
        } else if (node instanceof Node.Container c) {
            List<ByteSource> parts = materialiseChildren(c.children(), depth);
            ContainerCodec codec = Codecs.of(c.format());
            codec.rebuild(c.meta(), parts, out, depth == 0 ? threads : 1);
        } else {
            throw new IOException("unknown node type " + node.getClass());
        }
    }

    /**
     * Turns the children of a container into re-readable sources.
     *
     * <p>Blob children cost nothing: they are served straight out of the block store. Container
     * children have to be rebuilt into a temp file first, and that is the work worth spreading
     * over threads. Only the outermost container does so, for the same reason as in the
     * decomposer: a fixed pool whose tasks wait on subtasks of the same pool can deadlock, and
     * one level already covers every nested archive.
     */
    private List<ByteSource> materialiseChildren(List<Node> children, int depth) throws IOException {
        ByteSource[] parts = new ByteSource[children.size()];
        List<Integer> nested = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            if (child instanceof Node.Blob b) {
                parts[i] = new ChunkedByteSource(b.chunks(), b.size(), chunks);
            } else {
                nested.add(i);
            }
        }

        if (threads <= 1 || depth != 0 || nested.size() < 2) {
            for (int i : nested) {
                parts[i] = spill(children.get(i), depth + 1);
            }
            return Arrays.asList(parts);
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, nested.size()));
        try {
            List<Future<ByteSource>> futures = new ArrayList<>(nested.size());
            for (int i : nested) {
                Node child = children.get(i);
                futures.add(pool.submit(() -> spill(child, depth + 1)));
            }
            for (int k = 0; k < nested.size(); k++) {
                parts[nested.get(k)] = await(futures.get(k));
            }
            return Arrays.asList(parts);
        } finally {
            pool.shutdownNow();
        }
    }

    private ByteSource spill(Node node, int depth) throws IOException {
        return wd.spill(out -> write(node, out, depth));
    }

    private static ByteSource await(Future<ByteSource> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("rebuild interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("rebuild failed", cause);
        }
    }

    /** True if every block the blueprint needs is available. */
    public static List<Hash> missingChunks(Blueprint bp, ChunkSource source) {
        List<Hash> missing = new ArrayList<>();
        for (Hash h : bp.distinctChunks().keySet()) {
            if (!source.contains(h)) {
                missing.add(h);
            }
        }
        return missing;
    }
}
