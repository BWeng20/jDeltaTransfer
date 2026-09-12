package com.bw.jdt.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Deterministic, reproducible file content for the generated test archives.
 *
 * <p>Content is produced per 64 KiB block from a seed derived from the file id, the block index
 * and how many of the file's revisions touched that block. A new revision therefore rewrites
 * only a few blocks and leaves the rest byte identical -- the same way a patched binary or an
 * updated resource behaves. That is what the delta transfer is supposed to exploit, so the test
 * data has to behave like it.
 */
public final class Content {

    public static final int BLOCK = 64 * 1024;

    public enum Kind {
        /** Incompressible payload, stands in for already compressed or encrypted data. */
        BINARY,
        /** Highly compressible, text like payload. */
        TEXT
    }

    private static final String[] WORDS = {
            "module", "service", "handler", "buffer", "segment", "record", "index", "cache",
            "transfer", "delta", "archive", "cabinet", "payload", "checksum", "version", "block",
            "resource", "manifest", "library", "runtime", "config", "session", "request", "stream",
            "commit", "revision", "package", "descriptor", "catalog", "bundle", "layout", "table"
    };

    private Content() {
    }

    /** Splitmix64, used to derive independent seeds from small integers. */
    public static long mix(long a, long b, long c) {
        long z = a * 0x9E3779B97F4A7C15L + b * 0xBF58476D1CE4E5B9L + c * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * How many of the revisions {@code 1..revision} rewrote block {@code block}.
     *
     * <p>Each revision dirties a short run of consecutive blocks at a pseudo random position.
     */
    public static int blockRevision(long id, int revision, long block, long totalBlocks) {
        if (totalBlocks <= 0) {
            return revision;
        }
        int touched = 0;
        for (int r = 1; r <= revision; r++) {
            long h = mix(id, r, 0x5EED);
            long start = Math.floorMod(h, totalBlocks);
            long run = 1 + Math.floorMod(mix(id, r, 0xA11C), 4L);
            if (block >= start && block < start + run) {
                touched++;
            }
        }
        return touched;
    }

    public static InputStream open(long id, int revision, long size, Kind kind) {
        return new ContentStream(id, revision, size, kind);
    }

    /** CRC-32 of the content, needed to write STORED ZIP entries without buffering them. */
    public static long crc32(long id, int revision, long size, Kind kind) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[BLOCK];
        try (InputStream in = open(id, revision, size, kind)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }

    private static final class ContentStream extends InputStream {
        private final long id;
        private final int revision;
        private final long size;
        private final Kind kind;
        private final long totalBlocks;
        private final byte[] block = new byte[BLOCK];
        private long produced;
        private int blockLen;
        private int blockPos;
        private long blockIndex = -1;

        ContentStream(long id, int revision, long size, Kind kind) {
            this.id = id;
            this.revision = revision;
            this.size = size;
            this.kind = kind;
            this.totalBlocks = (size + BLOCK - 1) / BLOCK;
        }

        private boolean fill() {
            if (blockPos < blockLen) {
                return true;
            }
            if (produced >= size) {
                return false;
            }
            blockIndex++;
            int len = (int) Math.min(BLOCK, size - produced);
            long seed = mix(id, blockIndex, blockRevision(id, revision, blockIndex, totalBlocks));
            if (kind == Kind.BINARY) {
                fillBinary(block, len, seed);
            } else {
                fillText(block, len, seed);
            }
            blockLen = len;
            blockPos = 0;
            produced += len;
            return true;
        }

        @Override
        public int read() {
            if (!fill()) {
                return -1;
            }
            return block[blockPos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, blockLen - blockPos);
            System.arraycopy(block, blockPos, b, off, n);
            blockPos += n;
            return n;
        }
    }

    private static void fillBinary(byte[] out, int len, long seed) {
        long s = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        int i = 0;
        while (i + 8 <= len) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            long v = s * 0x2545F4914F6CDD1DL;
            out[i] = (byte) v;
            out[i + 1] = (byte) (v >>> 8);
            out[i + 2] = (byte) (v >>> 16);
            out[i + 3] = (byte) (v >>> 24);
            out[i + 4] = (byte) (v >>> 32);
            out[i + 5] = (byte) (v >>> 40);
            out[i + 6] = (byte) (v >>> 48);
            out[i + 7] = (byte) (v >>> 56);
            i += 8;
        }
        while (i < len) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            out[i++] = (byte) (s * 0x2545F4914F6CDD1DL);
        }
    }

    private static void fillText(byte[] out, int len, long seed) {
        long s = seed == 0 ? 0x1234567890ABCDEFL : seed;
        int i = 0;
        int lineLen = 0;
        while (i < len) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            byte[] word = WORDS[(int) Math.floorMod(s, WORDS.length)].getBytes(StandardCharsets.US_ASCII);
            for (byte c : word) {
                if (i >= len) {
                    return;
                }
                out[i++] = c;
                lineLen++;
            }
            if (i >= len) {
                return;
            }
            if (lineLen > 72) {
                out[i++] = '\n';
                lineLen = 0;
            } else {
                out[i++] = ' ';
            }
        }
    }
}
