package com.bw.jdt.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A re-readable sequence of bytes. Archives handled here are gigabytes in size, so payloads
 * are never assumed to fit in memory: large intermediate results live in temp files and are
 * exposed through {@link FileByteSource}.
 */
public interface ByteSource {

    long size();

    InputStream openStream() throws IOException;

    default void copyTo(OutputStream out) throws IOException {
        try (InputStream in = openStream()) {
            in.transferTo(out);
        }
    }

    /** Reads the whole source into memory. Only call this for sources known to be small. */
    default byte[] readAll() throws IOException {
        long n = size();
        if (n > Integer.MAX_VALUE - 8) {
            throw new IOException("source too large to materialise: " + n);
        }
        try (InputStream in = openStream()) {
            return in.readAllBytes();
        }
    }

    static ByteSource of(byte[] data) {
        return new MemoryByteSource(data, 0, data.length);
    }

    static ByteSource of(byte[] data, int off, int len) {
        return new MemoryByteSource(data, off, len);
    }

    static ByteSource ofFile(Path file) throws IOException {
        return new FileByteSource(file, 0, Files.size(file));
    }

    static ByteSource empty() {
        return new MemoryByteSource(new byte[0], 0, 0);
    }

    final class MemoryByteSource implements ByteSource {
        private final byte[] data;
        private final int off;
        private final int len;

        MemoryByteSource(byte[] data, int off, int len) {
            this.data = data;
            this.off = off;
            this.len = len;
        }

        @Override
        public long size() {
            return len;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(data, off, len);
        }

        @Override
        public byte[] readAll() {
            byte[] copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
            return copy;
        }
    }

    final class FileByteSource implements ByteSource {
        private final Path file;
        private final long off;
        private final long len;

        public FileByteSource(Path file, long off, long len) {
            this.file = file;
            this.off = off;
            this.len = len;
        }

        public Path file() {
            return file;
        }

        public long offset() {
            return off;
        }

        @Override
        public long size() {
            return len;
        }

        @Override
        public InputStream openStream() throws IOException {
            InputStream in = Files.newInputStream(file);
            try {
                if (off > 0) {
                    in.skipNBytes(off);
                }
            } catch (IOException e) {
                in.close();
                throw e;
            }
            return new BufferedInputStream(new BoundedInputStream(in, len), 1 << 16);
        }
    }

    /** Limits a stream to {@code limit} bytes and closes the delegate with it. */
    final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        public BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
