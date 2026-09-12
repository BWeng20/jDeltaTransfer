package com.bw.jdt.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * A re-readable view of a {@link Node.Blob}: its bytes are produced from the block store on
 * demand. Reassembly therefore never has to materialise leaf payloads to disk.
 */
public final class ChunkedByteSource implements ByteSource {

    private final List<ChunkRef> chunks;
    private final long size;
    private final ChunkSource source;

    public ChunkedByteSource(List<ChunkRef> chunks, long size, ChunkSource source) {
        this.chunks = chunks;
        this.size = size;
        this.source = source;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public InputStream openStream() {
        return new InputStream() {
            private final Iterator<ChunkRef> it = chunks.iterator();
            private byte[] current = new byte[0];
            private int pos;

            private boolean advance() throws IOException {
                while (pos >= current.length) {
                    if (!it.hasNext()) {
                        return false;
                    }
                    current = source.get(it.next().hash());
                    pos = 0;
                }
                return true;
            }

            @Override
            public int read() throws IOException {
                if (!advance()) {
                    return -1;
                }
                return current[pos++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (!advance()) {
                    return -1;
                }
                int n = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }
}
