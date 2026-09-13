package com.bw.jdt.proto;

import com.bw.jdt.core.Hash;
import com.bw.jdt.core.Hashes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Framing for block transfer.
 *
 * <p>Every payload -- delta blocks as well as a full archive download -- is sent as a sequence
 * of self describing frames:
 *
 * <pre>
 *   frame := 0x01 | hash[32] | length:int32 | payload[length]
 *   end   := 0x00 | frameCount:int64 | totalBytes:int64
 * </pre>
 *
 * The hash lets the receiver verify each block on arrival instead of only at the end, and the
 * trailer lets it tell a complete transfer apart from a truncated one. Frame payloads never
 * exceed the server's configured maximum block size.
 */
public final class Wire {

    public static final byte FRAME_BLOCK = 1;
    public static final byte FRAME_END = 0;

    /**
     * Transport compression. Blocks carry <em>decompressed</em> content -- that is what makes
     * deduplication work -- so sending them raw puts more bytes on the wire than the archive's own
     * compressed growth. Compressing the framed stream fixes that without touching the framing:
     * the per block hashes are over the uncompressed payload, so verification is unaffected.
     *
     * <p>Negotiated with the standard HTTP headers, so a peer that does not know about it simply
     * gets, or sends, an uncompressed stream.
     */
    public static final String ENCODING_GZIP = "gzip";

    /**
     * Default compression level. Measured on this project's block store, level 1 gives 78.9% of
     * the original at 62 MB/s while level 6 gives 77.2% at 39 MB/s: nearly the same ratio for far
     * more headroom. Raise it for text heavy archives, where the higher levels earn their keep.
     */
    public static final int DEFAULT_COMPRESSION_LEVEL = 1;

    public static final String CONTENT_TYPE_BLOCKS = "application/x-jdt-blocks";
    public static final String CONTENT_TYPE_HASHES = "application/x-jdt-hashes";
    public static final String CONTENT_TYPE_BLUEPRINT = "application/x-jdt-blueprint";

    private static final byte[] HASH_LIST_MAGIC = {'J', 'D', 'T', 'H', '1'};

    private Wire() {
    }

    // ----------------------------------------------------------- compression

    /**
     * Gzip wrapper with an explicit level. {@link GZIPOutputStream} offers no constructor for the
     * level, so the deflater it inherits is adjusted right after construction -- before any
     * payload has been written, so nothing is flushed at the wrong setting.
     */
    public static OutputStream gzip(OutputStream out, int level) throws IOException {
        return new GZIPOutputStream(out, 1 << 16) {
            {
                def.setLevel(level);
            }
        };
    }

    /** True if {@code acceptEncoding} asks for gzip. Null and blank mean "no". */
    public static boolean acceptsGzip(String acceptEncoding) {
        return acceptEncoding != null
                && acceptEncoding.toLowerCase(java.util.Locale.ROOT).contains(ENCODING_GZIP);
    }

    /** Counts the bytes that actually cross the wire, i.e. before decompression. */
    public static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;

        public CountingInputStream(InputStream in) {
            super(in);
        }

        public long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    // ------------------------------------------------------------- hash list

    public static void writeHashList(Collection<Hash> hashes, OutputStream raw) throws IOException {
        DataOutputStream out = new DataOutputStream(raw);
        out.write(HASH_LIST_MAGIC);
        out.writeInt(hashes.size());
        for (Hash h : hashes) {
            out.write(h.toBytes());
        }
        out.flush();
    }

    public static List<Hash> readHashList(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = in.readNBytes(HASH_LIST_MAGIC.length);
        if (!java.util.Arrays.equals(magic, HASH_LIST_MAGIC)) {
            throw new IOException("not a jDeltaTransfer hash list");
        }
        int count = in.readInt();
        if (count < 0 || count > 50_000_000) {
            throw new IOException("implausible hash count: " + count);
        }
        List<Hash> hashes = new ArrayList<>(Math.min(count, 1 << 20));
        for (int i = 0; i < count; i++) {
            hashes.add(Hash.wrap(in.readNBytes(Hash.LENGTH)));
        }
        return hashes;
    }

    // ---------------------------------------------------------------- frames

    /** Writes frames and the trailer; enforces the configured block size limit. */
    public static final class BlockWriter implements AutoCloseable {
        private final DataOutputStream out;
        private final int maxBlockSize;
        private long frames;
        private long bytes;

        public BlockWriter(OutputStream out, int maxBlockSize) {
            this.out = new DataOutputStream(out);
            this.maxBlockSize = maxBlockSize;
        }

        public void write(Hash hash, byte[] payload, int off, int len) throws IOException {
            if (len > maxBlockSize) {
                throw new IOException("block of " + len + " bytes exceeds the server limit of " + maxBlockSize);
            }
            out.writeByte(FRAME_BLOCK);
            out.write(hash.toBytes());
            out.writeInt(len);
            out.write(payload, off, len);
            frames++;
            bytes += len;
        }

        public void write(Hash hash, byte[] payload) throws IOException {
            write(hash, payload, 0, payload.length);
        }

        public long frames() {
            return frames;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public void close() throws IOException {
            out.writeByte(FRAME_END);
            out.writeLong(frames);
            out.writeLong(bytes);
            out.flush();
        }
    }

    /** Callback for a received, already verified block. */
    @FunctionalInterface
    public interface BlockHandler {
        void accept(Hash hash, byte[] payload) throws IOException;
    }

    /**
     * Reads frames until the trailer, verifying every block against its announced hash.
     *
     * @return number of payload bytes received
     */
    public static long readBlocks(InputStream raw, int maxBlockSize, BlockHandler handler) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        long frames = 0;
        long bytes = 0;
        while (true) {
            int tag = in.read();
            if (tag < 0) {
                throw new IOException("block stream truncated after " + frames + " blocks");
            }
            if (tag == FRAME_END) {
                long expectedFrames = in.readLong();
                long expectedBytes = in.readLong();
                if (expectedFrames != frames || expectedBytes != bytes) {
                    throw new IOException("block stream trailer mismatch: got " + frames + "/" + bytes
                            + ", announced " + expectedFrames + "/" + expectedBytes);
                }
                return bytes;
            }
            if (tag != FRAME_BLOCK) {
                throw new IOException("unknown frame tag " + tag);
            }
            Hash announced = Hash.wrap(in.readNBytes(Hash.LENGTH));
            int len = in.readInt();
            if (len < 0 || len > maxBlockSize) {
                throw new IOException("block length " + len + " outside the negotiated limit " + maxBlockSize);
            }
            byte[] payload = in.readNBytes(len);
            if (payload.length != len) {
                throw new IOException("block stream truncated inside a block");
            }
            Hash actual = Hashes.of(payload);
            if (!actual.equals(announced)) {
                throw new IOException("block hash mismatch: announced " + announced + ", got " + actual);
            }
            handler.accept(announced, payload);
            frames++;
            bytes += len;
        }
    }
}
