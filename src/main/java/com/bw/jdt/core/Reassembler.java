package com.bw.jdt.core;

import com.bw.jdt.core.format.ContainerCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Rebuilds an archive from a {@link Blueprint} plus the blocks it references. */
public final class Reassembler {

    private final ChunkSource chunks;
    private final WorkDir wd;

    public Reassembler(ChunkSource chunks, WorkDir wd) {
        this.chunks = chunks;
        this.wd = wd;
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
        if (node instanceof Node.Blob b) {
            for (ChunkRef ref : b.chunks()) {
                out.write(chunks.get(ref.hash()));
            }
        } else if (node instanceof Node.Container c) {
            List<ByteSource> parts = new ArrayList<>(c.children().size());
            for (Node child : c.children()) {
                parts.add(materialise(child));
            }
            ContainerCodec codec = Codecs.of(c.format());
            codec.rebuild(c.meta(), parts, out);
        } else {
            throw new IOException("unknown node type " + node.getClass());
        }
    }

    /**
     * Makes a node available as a re-readable source. Leaf payloads are served straight out of
     * the block store; only nested containers need to be built into a temp file first.
     */
    private ByteSource materialise(Node node) throws IOException {
        if (node instanceof Node.Blob b) {
            return new ChunkedByteSource(b.chunks(), b.size(), chunks);
        }
        return wd.spill(out -> write(node, out));
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
