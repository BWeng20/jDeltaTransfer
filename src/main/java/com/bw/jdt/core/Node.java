package com.bw.jdt.core;

import java.util.List;

/**
 * A node of the blueprint, the recursive description of one archive.
 *
 * <p>A {@link Container} is an archive that was understood well enough to be taken apart and
 * put back together byte for byte. Its children are the <em>decompressed</em> payloads of its
 * entries, which is what makes the delta small: a one byte edit inside a nested CAB changes a
 * few chunks of one child, not the whole compressed stream.
 *
 * <p>A {@link Blob} is a plain byte range split into content defined chunks. Everything the
 * decomposer cannot reproduce bit exactly ends up as a blob, which keeps correctness
 * unconditional and merely costs delta efficiency.
 */
public sealed interface Node {

    /** Size in bytes of the reconstructed payload this node represents. */
    long size();

    record Blob(long size, List<ChunkRef> chunks) implements Node {
        public Blob {
            chunks = List.copyOf(chunks);
        }
    }

    record Container(ContainerFormat format, byte[] meta, List<Node> children, long size) implements Node {
        public Container {
            children = List.copyOf(children);
        }
    }
}
