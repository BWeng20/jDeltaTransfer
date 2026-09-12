package com.bw.jdt.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content addressed block store backed by a single append-only pack file plus an index.
 *
 * <p>A per-chunk file layout would mean millions of tiny files for gigabyte sized archives,
 * which NTFS handles poorly; one pack keeps both ingest and serving fast. The index is
 * replayed into memory on open, so lookups are a hash map hit.
 *
 * <p>Reads use positional channel reads and are safe from multiple threads; writes are
 * serialised on the store instance.
 */
public final class ChunkStore implements ChunkSource, Closeable {

    private static final int INDEX_RECORD = Hash.LENGTH + 8 + 4;

    private final Path dir;
    private final FileChannel pack;
    private final FileChannel index;
    private final Map<Hash, Loc> locations = new ConcurrentHashMap<>();
    private final AtomicLong packEnd = new AtomicLong();

    private record Loc(long offset, int length) {
    }

    private ChunkStore(Path dir, FileChannel pack, FileChannel index) {
        this.dir = dir;
        this.pack = pack;
        this.index = index;
    }

    public static ChunkStore open(Path dir) throws IOException {
        Files.createDirectories(dir);
        FileChannel pack = FileChannel.open(dir.resolve("data.pack"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileChannel index = FileChannel.open(dir.resolve("index.bin"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        ChunkStore store = new ChunkStore(dir, pack, index);
        store.replayIndex();
        return store;
    }

    private void replayIndex() throws IOException {
        long size = index.size();
        long usable = size - (size % INDEX_RECORD);
        if (usable != size) {
            // A crash during append can leave a partial record behind; drop it.
            index.truncate(usable);
        }
        long packSize = pack.size();
        int batch = (1 << 20) / INDEX_RECORD * INDEX_RECORD;
        ByteBuffer buf = ByteBuffer.allocate(batch);
        long pos = 0;
        long end = 0;
        long good = 0;
        boolean torn = false;
        while (pos < usable && !torn) {
            int want = (int) Math.min(batch, usable - pos);
            buf.clear();
            buf.limit(want);
            readFully(index, buf, pos);
            buf.flip();
            while (buf.remaining() >= INDEX_RECORD) {
                byte[] h = new byte[Hash.LENGTH];
                buf.get(h);
                long off = buf.getLong();
                int len = buf.getInt();
                if (off < 0 || len < 0 || off + len > packSize) {
                    // Index entry without payload (torn write): everything after it is unusable.
                    torn = true;
                    break;
                }
                locations.put(Hash.wrap(h), new Loc(off, len));
                end = Math.max(end, off + len);
                good += INDEX_RECORD;
            }
            pos += want;
        }
        packEnd.set(end);
        if (torn) {
            index.truncate(good);
        }
        if (packSize > end) {
            pack.truncate(end);
        }
        index.position(index.size());
    }

    public Path dir() {
        return dir;
    }

    @Override
    public boolean contains(Hash hash) {
        return locations.containsKey(hash);
    }

    public int chunkCount() {
        return locations.size();
    }

    public long storedBytes() {
        return packEnd.get();
    }

    /** Stores a chunk unless an identical one is already present. Returns true if newly written. */
    public synchronized boolean put(Hash hash, byte[] data, int off, int len) throws IOException {
        if (locations.containsKey(hash)) {
            return false;
        }
        long offset = packEnd.get();
        writeFully(pack, ByteBuffer.wrap(data, off, len), offset);
        ByteBuffer rec = ByteBuffer.allocate(INDEX_RECORD);
        rec.put(hash.toBytes()).putLong(offset).putInt(len).flip();
        writeFully(index, rec, index.size());
        packEnd.set(offset + len);
        locations.put(hash, new Loc(offset, len));
        return true;
    }

    public boolean put(Hash hash, byte[] data) throws IOException {
        return put(hash, data, 0, data.length);
    }

    public int lengthOf(Hash hash) throws IOException {
        Loc loc = locations.get(hash);
        if (loc == null) {
            throw new IOException("chunk not in store: " + hash);
        }
        return loc.length();
    }

    @Override
    public byte[] get(Hash hash) throws IOException {
        Loc loc = locations.get(hash);
        if (loc == null) {
            throw new IOException("chunk not in store: " + hash);
        }
        ByteBuffer buf = ByteBuffer.allocate(loc.length());
        readFully(pack, buf, loc.offset());
        return buf.array();
    }

    public void copyTo(Hash hash, OutputStream out) throws IOException {
        out.write(get(hash));
    }

    /** Flushes pack and index so a crash cannot lose acknowledged chunks. */
    public synchronized void sync() throws IOException {
        pack.force(false);
        index.force(false);
    }

    @Override
    public void close() throws IOException {
        try {
            sync();
        } finally {
            pack.close();
            index.close();
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new IOException("unexpected end of file at " + pos);
            }
            pos += n;
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            pos += ch.write(buf, pos);
        }
    }
}
