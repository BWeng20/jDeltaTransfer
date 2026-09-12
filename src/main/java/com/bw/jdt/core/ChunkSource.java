package com.bw.jdt.core;

import java.io.IOException;
import java.util.List;

/** Read side of a block store. The client reassembles from several of these at once. */
public interface ChunkSource {

    boolean contains(Hash hash);

    byte[] get(Hash hash) throws IOException;

    /** Tries each delegate in order; the first that has the block wins. */
    static ChunkSource composite(List<ChunkSource> sources) {
        return new ChunkSource() {
            @Override
            public boolean contains(Hash hash) {
                for (ChunkSource s : sources) {
                    if (s.contains(hash)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public byte[] get(Hash hash) throws IOException {
                for (ChunkSource s : sources) {
                    if (s.contains(hash)) {
                        return s.get(hash);
                    }
                }
                throw new IOException("block missing from every source: " + hash);
            }
        };
    }
}
