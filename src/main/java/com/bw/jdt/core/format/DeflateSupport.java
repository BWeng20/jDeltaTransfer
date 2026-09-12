package com.bw.jdt.core.format;

import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.WorkDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Raw deflate helpers plus the level probe that makes exact rebuilds possible.
 *
 * <p>Re-compressing an inflated stream only reproduces the original bytes if the same
 * compression level (and encoder) was used. Rather than guessing, the decomposer compresses
 * the payload and compares it against the original, aborting a candidate level at the first
 * differing byte. A payload for which no level matches is kept as opaque compressed bytes.
 */
public final class DeflateSupport {

    /** Probe order: zlib/Java default first, then the other common choices. */
    public static final int[] LEVEL_ORDER = {6, 9, 1, 5, 4, 3, 2, 7, 8};

    /** Sentinel meaning "no level reproduces the original". */
    public static final int NO_LEVEL = -1;

    private DeflateSupport() {
    }

    public static ByteSource inflate(ByteSource compressed, long expectedSize, WorkDir wd) throws IOException {
        boolean small = expectedSize >= 0 && expectedSize <= (1 << 20);
        if (small) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) expectedSize);
            inflateTo(compressed, bos);
            byte[] data = bos.toByteArray();
            if (expectedSize >= 0 && data.length != expectedSize) {
                throw new IOException("inflated size mismatch: " + data.length + " != " + expectedSize);
            }
            return ByteSource.of(data);
        }
        ByteSource out = wd.spill(os -> inflateTo(compressed, os));
        if (expectedSize >= 0 && out.size() != expectedSize) {
            throw new IOException("inflated size mismatch: " + out.size() + " != " + expectedSize);
        }
        return out;
    }

    private static void inflateTo(ByteSource compressed, OutputStream out) throws IOException {
        Inflater inf = new Inflater(true);
        try (InputStream in = compressed.openStream()) {
            byte[] inBuf = new byte[1 << 16];
            byte[] outBuf = new byte[1 << 16];
            while (true) {
                if (inf.needsInput()) {
                    int n = in.read(inBuf);
                    if (n < 0) {
                        break;
                    }
                    inf.setInput(inBuf, 0, n);
                }
                boolean progressed = false;
                while (true) {
                    int n;
                    try {
                        n = inf.inflate(outBuf);
                    } catch (DataFormatException e) {
                        throw new IOException("corrupt deflate stream", e);
                    }
                    if (n == 0) {
                        break;
                    }
                    out.write(outBuf, 0, n);
                    progressed = true;
                }
                if (inf.finished()) {
                    break;
                }
                if (!progressed && !inf.needsInput()) {
                    break;
                }
            }
        } finally {
            inf.end();
        }
    }

    public static void deflate(ByteSource raw, int level, OutputStream out) throws IOException {
        Deflater def = new Deflater(level, true);
        try (InputStream in = raw.openStream()) {
            byte[] inBuf = new byte[1 << 16];
            byte[] outBuf = new byte[1 << 16];
            int n;
            while ((n = in.read(inBuf)) > 0) {
                def.setInput(inBuf, 0, n);
                while (!def.needsInput()) {
                    int w = def.deflate(outBuf);
                    if (w > 0) {
                        out.write(outBuf, 0, w);
                    }
                }
            }
            def.finish();
            while (!def.finished()) {
                int w = def.deflate(outBuf);
                if (w > 0) {
                    out.write(outBuf, 0, w);
                }
            }
        } finally {
            def.end();
        }
    }

    /**
     * Finds the deflate level whose output equals {@code compressed} byte for byte.
     *
     * @param preferred level to try first (typically the one that matched the previous entry
     *                  of the same archive), or {@link #NO_LEVEL}
     * @return the matching level, or {@link #NO_LEVEL}
     */
    public static int probeLevel(ByteSource inflated, ByteSource compressed, int preferred) throws IOException {
        if (preferred >= 0 && matches(inflated, compressed, preferred)) {
            return preferred;
        }
        for (int level : LEVEL_ORDER) {
            if (level == preferred) {
                continue;
            }
            if (matches(inflated, compressed, level)) {
                return level;
            }
        }
        return NO_LEVEL;
    }

    private static boolean matches(ByteSource inflated, ByteSource compressed, int level) throws IOException {
        try (InputStream expected = compressed.openStream()) {
            ComparingOutputStream cmp = new ComparingOutputStream(expected, compressed.size());
            try {
                deflate(inflated, level, cmp);
            } catch (Mismatch e) {
                return false;
            }
            return cmp.complete();
        }
    }

    /** Swallowed internally; signals that the candidate level already diverged. */
    private static final class Mismatch extends IOException {
        private static final long serialVersionUID = 1L;

        static final Mismatch INSTANCE = new Mismatch();

        private Mismatch() {
            super("deflate level candidate diverged");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Thrown once per rejected level; a stack trace would be pure overhead.
            return this;
        }
    }

    /** Compares everything written against a reference stream and fails fast. */
    private static final class ComparingOutputStream extends OutputStream {
        private final InputStream expected;
        private final long expectedLength;
        private final byte[] buf = new byte[1 << 16];
        private long written;

        ComparingOutputStream(InputStream expected, long expectedLength) {
            this.expected = expected;
            this.expectedLength = expectedLength;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written + len > expectedLength) {
                throw Mismatch.INSTANCE;
            }
            int done = 0;
            while (done < len) {
                int want = Math.min(buf.length, len - done);
                int got = expected.readNBytes(buf, 0, want);
                if (got != want) {
                    throw Mismatch.INSTANCE;
                }
                if (!java.util.Arrays.equals(buf, 0, want, b, off + done, off + done + want)) {
                    throw Mismatch.INSTANCE;
                }
                done += want;
            }
            written += len;
        }

        boolean complete() {
            return written == expectedLength;
        }
    }
}
