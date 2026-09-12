package com.bw.jdt.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

/**
 * Content defined chunking (FastCDC with normalised chunk sizes).
 *
 * <p>Cut points are derived from the content itself, not from a fixed grid, so inserting or
 * removing bytes only disturbs the chunks around the edit instead of shifting every following
 * block. That is what makes the delta between two archive versions small.
 *
 * <p>Block sizes are therefore dynamic. {@link Params#of(int, int)} clamps them against the
 * hard upper bound the server was started with, so no block on the wire can ever exceed it.
 */
public final class Chunker {

    /** Gear table, deterministic across JVMs so client and server cut at identical offsets. */
    private static final long[] GEAR = buildGear();

    private final Params params;
    private final long maskStrict;
    private final long maskLenient;

    public Chunker(Params params) {
        this.params = params;
        int bits = 31 - Integer.numberOfLeadingZeros(params.avg());
        this.maskStrict = maskFor(bits + 2);
        this.maskLenient = maskFor(bits - 2);
    }

    public Params params() {
        return params;
    }

    /**
     * Chunk size configuration.
     *
     * @param min minimum chunk size, no cut point is searched below it
     * @param avg target average chunk size
     * @param max hard maximum, never exceeded
     */
    public record Params(int min, int avg, int max) {
        public Params {
            if (min < 64 || avg < min || max < avg) {
                throw new IllegalArgumentException("invalid chunk params: min=" + min + " avg=" + avg + " max=" + max);
            }
        }

        /**
         * Derives parameters from a desired average size, clamped so that {@code max} never
         * exceeds {@code hardMax} (the server's {@code --max-block-size}).
         */
        public static Params of(int desiredAvg, int hardMax) {
            if (hardMax < 1024) {
                throw new IllegalArgumentException("max block size must be at least 1024 bytes");
            }
            int avg = Integer.highestOneBit(Math.max(1024, Math.min(desiredAvg, hardMax)));
            int max = Math.min(hardMax, Math.max(avg * 4, avg + 1));
            int min = Math.max(64, avg / 4);
            if (min > avg) {
                min = avg;
            }
            return new Params(min, avg, max);
        }
    }

    @FunctionalInterface
    public interface Sink {
        /** Called once per chunk; {@code buf} is reused, so do not retain it. */
        void chunk(byte[] buf, int off, int len) throws IOException;
    }

    /** Splits the whole stream into chunks and hands each one to {@code sink} in order. */
    public void split(InputStream in, Sink sink) throws IOException {
        byte[] buf = new byte[params.max() * 2];
        int avail = 0;
        boolean eof = false;
        while (true) {
            while (!eof && avail < buf.length) {
                int n = in.read(buf, avail, buf.length - avail);
                if (n < 0) {
                    eof = true;
                } else {
                    avail += n;
                }
            }
            if (avail == 0) {
                return;
            }
            int cut = cutPoint(buf, avail, eof);
            sink.chunk(buf, 0, cut);
            avail -= cut;
            if (avail > 0) {
                System.arraycopy(buf, cut, buf, 0, avail);
            } else if (eof) {
                return;
            }
        }
    }

    /**
     * Push variant of {@link #split}: everything written to the returned stream is cut into
     * blocks and handed to {@code sink}. Lets a producer that only offers an
     * {@link OutputStream} -- such as the reassembler -- be chunked without a temp file in
     * between. The final partial block is emitted on {@code close()}.
     */
    public OutputStream newOutputStream(Sink sink) {
        return new OutputStream() {
            private final byte[] buf = new byte[params.max() * 2];
            private int avail;

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int pos = off;
                int remaining = len;
                while (remaining > 0) {
                    int n = Math.min(remaining, buf.length - avail);
                    System.arraycopy(b, pos, buf, avail, n);
                    avail += n;
                    pos += n;
                    remaining -= n;
                    while (avail == buf.length) {
                        int cut = cutPoint(buf, avail, false);
                        sink.chunk(buf, 0, cut);
                        avail -= cut;
                        System.arraycopy(buf, cut, buf, 0, avail);
                    }
                }
            }

            @Override
            public void close() throws IOException {
                while (avail > 0) {
                    int cut = cutPoint(buf, avail, true);
                    sink.chunk(buf, 0, cut);
                    avail -= cut;
                    if (avail > 0) {
                        System.arraycopy(buf, cut, buf, 0, avail);
                    }
                }
            }
        };
    }

    /** Finds the next cut point within {@code buf[0..avail)}. */
    private int cutPoint(byte[] buf, int avail, boolean eof) {
        if (eof && avail <= params.min()) {
            return avail;
        }
        int hardLimit = Math.min(avail, params.max());
        int normal = Math.min(params.avg(), hardLimit);
        long fp = 0;
        int i = params.min();
        for (; i < normal; i++) {
            fp = (fp << 1) + GEAR[buf[i] & 0xff];
            if ((fp & maskStrict) == 0) {
                return i;
            }
        }
        for (; i < hardLimit; i++) {
            fp = (fp << 1) + GEAR[buf[i] & 0xff];
            if ((fp & maskLenient) == 0) {
                return i;
            }
        }
        return hardLimit;
    }

    private static long maskFor(int bits) {
        int b = Math.max(2, Math.min(40, bits));
        return ((1L << b) - 1) << (63 - b);
    }

    private static long[] buildGear() {
        // java.util.Random has a specified algorithm, so this table is identical everywhere.
        Random rnd = new Random(0x3F5A7C21D9B4E60EL);
        long[] table = new long[256];
        for (int i = 0; i < table.length; i++) {
            table[i] = rnd.nextLong();
        }
        return table;
    }
}
