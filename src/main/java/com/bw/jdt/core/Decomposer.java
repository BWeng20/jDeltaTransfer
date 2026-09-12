package com.bw.jdt.core;

import com.bw.jdt.core.format.ArchiveSniffer;
import com.bw.jdt.core.format.ContainerCodec;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
    private final int maxDepth;
    private final Listener listener;

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
        this(store, params, DEFAULT_MAX_DEPTH, Listener.NOOP);
    }

    public Decomposer(ChunkStore store, Chunker.Params params, int maxDepth, Listener listener) {
        this.store = store;
        this.chunker = new Chunker(params);
        this.maxDepth = maxDepth;
        this.listener = listener;
    }

    public Blueprint decompose(ByteSource archive, WorkDir wd) throws IOException {
        Hash archiveHash = Hashes.of(archive);
        Node root = build(archive, 0, wd);
        return new Blueprint(archiveHash, archive.size(), chunker.params(), root);
    }

    private Node build(ByteSource src, int depth, WorkDir wd) throws IOException {
        if (depth < maxDepth && src.size() >= ArchiveSniffer.MIN_CONTAINER_SIZE) {
            ContainerFormat fmt = ArchiveSniffer.detect(src);
            if (fmt != null) {
                ContainerCodec codec = Codecs.of(fmt);
                ContainerCodec.Decomposition d = codec.decompose(src, wd);
                if (d != null) {
                    listener.onContainer(fmt, depth, src.size());
                    List<Node> children = new ArrayList<>(d.parts().size());
                    for (ByteSource part : d.parts()) {
                        children.add(build(part, depth + 1, wd));
                    }
                    return new Node.Container(fmt, d.meta(), children, src.size());
                }
                listener.onOpaque(fmt, depth, src.size());
            }
        }
        return blob(src);
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
