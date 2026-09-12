package com.bw.jdt.core.format;

import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ContainerFormat;
import com.bw.jdt.core.WorkDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a ZIP into a verbatim byte skeleton plus the decompressed payload of every entry.
 *
 * <p>The file is modelled as an ordered partition of {@code [0, size)}: every byte is either
 * part of an entry payload or of a "raw" region (local headers, data descriptors, the central
 * directory, the end of central directory record, padding). Raw regions are reproduced byte
 * for byte, and entry payloads are either copied verbatim or re-deflated at the level that was
 * proven during decomposition to reproduce the original stream. The rebuild is therefore exact
 * by construction, without a verification pass.
 *
 * <p>Anything not understood (encryption, exotic compression methods, a deflate stream no
 * level reproduces) stays in its original compressed form as a verbatim payload.
 */
public final class ZipCodec implements ContainerCodec {

    private static final int SIG_LFH = 0x04034b50;
    private static final int SIG_CDH = 0x02014b50;
    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_ZIP64_EOCD = 0x06064b50;
    private static final int SIG_ZIP64_LOCATOR = 0x07064b50;

    private static final int METHOD_STORE = 0;
    private static final int METHOD_DEFLATE = 8;

    /** Raw regions up to this size are embedded in the metadata instead of becoming a block. */
    private static final int INLINE_LIMIT = 4096;

    private static final byte SEG_INLINE = 0;
    private static final byte SEG_PART = 1;

    private static final byte TRANSFORM_VERBATIM = 0;
    private static final byte TRANSFORM_DEFLATE = 1;

    @Override
    public ContainerFormat format() {
        return ContainerFormat.ZIP;
    }

    @Override
    public Decomposition decompose(ByteSource src, WorkDir wd) throws IOException {
        try (RandomAccessSource ras = RandomAccessSource.open(src)) {
            List<CentralEntry> entries = readCentralDirectory(ras);
            if (entries == null) {
                return null;
            }
            entries.sort(Comparator.comparingLong(e -> e.localOffset));

            ByteArrayOutputStream metaBuf = new ByteArrayOutputStream(1 << 16);
            DataOutputStream meta = new DataOutputStream(metaBuf);
            List<Segment> segments = new ArrayList<>();
            List<ByteSource> parts = new ArrayList<>();

            long pos = 0;
            int lastLevel = DeflateSupport.NO_LEVEL;

            for (CentralEntry e : entries) {
                long dataStart = localDataStart(ras, e);
                if (dataStart < 0 || dataStart < pos || dataStart + e.compressedSize > ras.size()) {
                    return null;
                }
                addRaw(segments, parts, ras, pos, dataStart - pos);
                if (e.compressedSize == 0) {
                    // Directory entries and empty files contribute no bytes at all.
                    pos = dataStart;
                    continue;
                }

                ByteSource payload = ras.slice(dataStart, e.compressedSize);
                boolean encrypted = (e.flags & 0x1) != 0;
                if (!encrypted && e.method == METHOD_DEFLATE && e.compressedSize > 0) {
                    ByteSource inflated = tryInflate(payload, e.uncompressedSize, wd);
                    int level = inflated == null
                            ? DeflateSupport.NO_LEVEL
                            : DeflateSupport.probeLevel(inflated, payload, lastLevel);
                    if (level != DeflateSupport.NO_LEVEL) {
                        lastLevel = level;
                        segments.add(Segment.part(TRANSFORM_DEFLATE, level));
                        parts.add(inflated);
                        pos = dataStart + e.compressedSize;
                        continue;
                    }
                }
                // Stored, encrypted, unknown method, or not reproducible: keep the bytes as they are.
                segments.add(Segment.part(TRANSFORM_VERBATIM, 0));
                parts.add(payload);
                pos = dataStart + e.compressedSize;
            }
            addRaw(segments, parts, ras, pos, ras.size() - pos);

            meta.writeInt(segments.size());
            for (Segment s : segments) {
                if (s.inline != null) {
                    meta.writeByte(SEG_INLINE);
                    meta.writeInt(s.inline.length);
                    meta.write(s.inline);
                } else {
                    meta.writeByte(SEG_PART);
                    meta.writeByte(s.transform);
                    meta.writeByte(s.level);
                }
            }
            meta.flush();
            return new Decomposition(metaBuf.toByteArray(), parts);
        } catch (IOException e) {
            // A malformed ZIP is not an error: the caller falls back to opaque chunking.
            return null;
        }
    }

    @Override
    public void rebuild(byte[] metaBytes, List<ByteSource> parts, OutputStream out) throws IOException {
        DataInputStream meta = new DataInputStream(new java.io.ByteArrayInputStream(metaBytes));
        int segmentCount = meta.readInt();
        int partIndex = 0;
        for (int i = 0; i < segmentCount; i++) {
            int kind = meta.readUnsignedByte();
            if (kind == SEG_INLINE) {
                out.write(meta.readNBytes(meta.readInt()));
            } else {
                int transform = meta.readUnsignedByte();
                int level = meta.readUnsignedByte();
                if (partIndex >= parts.size()) {
                    throw new IOException("blueprint references more payloads than available");
                }
                ByteSource part = parts.get(partIndex++);
                if (transform == TRANSFORM_DEFLATE) {
                    DeflateSupport.deflate(part, level, out);
                } else {
                    part.copyTo(out);
                }
            }
        }
        if (partIndex != parts.size()) {
            throw new IOException("unused payloads in ZIP rebuild: " + (parts.size() - partIndex));
        }
    }

    private static void addRaw(List<Segment> segments, List<ByteSource> parts,
                               RandomAccessSource ras, long pos, long len) throws IOException {
        if (len <= 0) {
            return;
        }
        if (len <= INLINE_LIMIT) {
            segments.add(Segment.inline(ras.read(pos, (int) len)));
        } else {
            // Big raw regions (typically the central directory) become blocks of their own so
            // they take part in deduplication instead of being resent in full every time.
            segments.add(Segment.part(TRANSFORM_VERBATIM, 0));
            parts.add(ras.slice(pos, len));
        }
    }

    private static ByteSource tryInflate(ByteSource payload, long expectedSize, WorkDir wd) {
        try {
            return DeflateSupport.inflate(payload, expectedSize, wd);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- parsing

    private static final class Segment {
        final byte[] inline;
        final byte transform;
        final byte level;

        private Segment(byte[] inline, byte transform, byte level) {
            this.inline = inline;
            this.transform = transform;
            this.level = level;
        }

        static Segment inline(byte[] data) {
            return new Segment(data, (byte) 0, (byte) 0);
        }

        static Segment part(byte transform, int level) {
            return new Segment(null, transform, (byte) level);
        }
    }

    private static final class CentralEntry {
        long localOffset;
        long compressedSize;
        long uncompressedSize;
        int method;
        int flags;
    }

    /** @return the entries, or {@code null} if this is not a plain, single volume ZIP. */
    private static List<CentralEntry> readCentralDirectory(RandomAccessSource ras) throws IOException {
        long size = ras.size();
        int tailLen = (int) Math.min(size, 66000);
        byte[] tail = ras.read(size - tailLen, tailLen);
        int eocd = lastIndexOfSignature(tail, SIG_EOCD);
        if (eocd < 0) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        long entryCount = u16(b, eocd + 10);
        long cdSize = u32(b, eocd + 12);
        long cdOffset = u32(b, eocd + 16);

        if (entryCount == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            int loc = eocd - 20;
            if (loc < 0 || b.getInt(loc) != SIG_ZIP64_LOCATOR) {
                return null;
            }
            long z64Offset = b.getLong(loc + 8);
            if (z64Offset < 0 || z64Offset + 56 > size) {
                return null;
            }
            ByteBuffer z = ByteBuffer.wrap(ras.read(z64Offset, 56)).order(ByteOrder.LITTLE_ENDIAN);
            if (z.getInt(0) != SIG_ZIP64_EOCD) {
                return null;
            }
            entryCount = z.getLong(32);
            cdSize = z.getLong(40);
            cdOffset = z.getLong(48);
        }

        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > size || entryCount < 0 || entryCount > 20_000_000L) {
            return null;
        }
        if (cdSize > Integer.MAX_VALUE - 8) {
            return null;
        }

        byte[] cd = ras.read(cdOffset, (int) cdSize);
        ByteBuffer c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        List<CentralEntry> entries = new ArrayList<>((int) Math.min(entryCount, 1 << 20));
        int p = 0;
        for (long i = 0; i < entryCount; i++) {
            if (p + 46 > cd.length || c.getInt(p) != SIG_CDH) {
                return null;
            }
            CentralEntry e = new CentralEntry();
            e.flags = u16(c, p + 8);
            e.method = u16(c, p + 10);
            e.compressedSize = u32(c, p + 20);
            e.uncompressedSize = u32(c, p + 24);
            int nameLen = u16(c, p + 28);
            int extraLen = u16(c, p + 30);
            int commentLen = u16(c, p + 32);
            e.localOffset = u32(c, p + 42);
            int extraStart = p + 46 + nameLen;
            if (extraStart + extraLen + commentLen > cd.length) {
                return null;
            }
            applyZip64Extra(c, extraStart, extraLen, e);
            if (e.compressedSize < 0 || e.uncompressedSize < 0 || e.localOffset < 0) {
                return null;
            }
            entries.add(e);
            p = extraStart + extraLen + commentLen;
        }
        return entries;
    }

    /** Reads the ZIP64 extended information extra field and patches the 32 bit placeholders. */
    private static void applyZip64Extra(ByteBuffer cd, int start, int len, CentralEntry e) {
        int p = start;
        int end = start + len;
        while (p + 4 <= end) {
            int id = u16(cd, p);
            int dataLen = u16(cd, p + 2);
            if (p + 4 + dataLen > end) {
                return;
            }
            if (id == 0x0001) {
                int q = p + 4;
                if (e.uncompressedSize == 0xFFFFFFFFL && q + 8 <= end) {
                    e.uncompressedSize = cd.getLong(q);
                    q += 8;
                }
                if (e.compressedSize == 0xFFFFFFFFL && q + 8 <= end) {
                    e.compressedSize = cd.getLong(q);
                    q += 8;
                }
                if (e.localOffset == 0xFFFFFFFFL && q + 8 <= end) {
                    e.localOffset = cd.getLong(q);
                }
                return;
            }
            p += 4 + dataLen;
        }
    }

    /** Start offset of the payload of {@code e}, derived from its local file header. */
    private static long localDataStart(RandomAccessSource ras, CentralEntry e) throws IOException {
        if (e.localOffset + 30 > ras.size()) {
            return -1;
        }
        ByteBuffer lfh = ByteBuffer.wrap(ras.read(e.localOffset, 30)).order(ByteOrder.LITTLE_ENDIAN);
        if (lfh.getInt(0) != SIG_LFH) {
            return -1;
        }
        int nameLen = u16(lfh, 26);
        int extraLen = u16(lfh, 28);
        return e.localOffset + 30 + nameLen + extraLen;
    }

    private static int lastIndexOfSignature(byte[] data, int signature) {
        byte b0 = (byte) signature;
        byte b1 = (byte) (signature >>> 8);
        byte b2 = (byte) (signature >>> 16);
        byte b3 = (byte) (signature >>> 24);
        for (int i = data.length - 4; i >= 0; i--) {
            if (data[i] == b0 && data[i + 1] == b1 && data[i + 2] == b2 && data[i + 3] == b3) {
                return i;
            }
        }
        return -1;
    }

    private static int u16(ByteBuffer b, int pos) {
        return b.getShort(pos) & 0xFFFF;
    }

    private static long u32(ByteBuffer b, int pos) {
        return b.getInt(pos) & 0xFFFFFFFFL;
    }
}
