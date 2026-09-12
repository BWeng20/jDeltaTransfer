package com.bw.jdt.core.format;

import com.bw.jdt.core.ByteSource;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Random access view over a {@link ByteSource}. File backed sources are read positionally and
 * sliced without copying, so a 1 GiB archive can be taken apart without materialising it.
 */
public final class RandomAccessSource implements Closeable {

    private final FileChannel channel;
    private final Path file;
    private final long base;
    private final long length;
    private final byte[] memory;

    private RandomAccessSource(FileChannel channel, Path file, long base, long length, byte[] memory) {
        this.channel = channel;
        this.file = file;
        this.base = base;
        this.length = length;
        this.memory = memory;
    }

    public static RandomAccessSource open(ByteSource src) throws IOException {
        if (src instanceof ByteSource.FileByteSource f) {
            FileChannel ch = FileChannel.open(f.file(), StandardOpenOption.READ);
            if (f.offset() + f.size() > ch.size()) {
                ch.close();
                throw new IOException("file source extends past end of file " + f.file());
            }
            return new RandomAccessSource(ch, f.file(), f.offset(), f.size(), null);
        }
        return new RandomAccessSource(null, null, 0, src.size(), src.readAll());
    }

    public long size() {
        return length;
    }

    public byte[] read(long pos, int len) throws IOException {
        if (pos < 0 || len < 0 || pos + len > length) {
            throw new IOException("read out of bounds: pos=" + pos + " len=" + len + " size=" + length);
        }
        byte[] dst = new byte[len];
        if (memory != null) {
            System.arraycopy(memory, (int) pos, dst, 0, len);
            return dst;
        }
        ByteBuffer buf = ByteBuffer.wrap(dst);
        long p = base + pos;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, p);
            if (n < 0) {
                throw new IOException("unexpected end of file");
            }
            p += n;
        }
        return dst;
    }

    /** A zero copy view of {@code [pos, pos+len)}. */
    public ByteSource slice(long pos, long len) throws IOException {
        if (pos < 0 || len < 0 || pos + len > length) {
            throw new IOException("slice out of bounds: pos=" + pos + " len=" + len + " size=" + length);
        }
        if (memory != null) {
            return ByteSource.of(memory, (int) pos, (int) len);
        }
        return new ByteSource.FileByteSource(file, base + pos, len);
    }

    public InputStream openStream(long pos, long len) throws IOException {
        return slice(pos, len).openStream();
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
        }
    }
}
