package com.bw.jdt.core.format;

import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ContainerFormat;
import com.bw.jdt.core.DecomposeLimits;
import com.bw.jdt.core.WorkDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Microsoft Cabinet (MSCF) codec. Apache Commons Compress does not cover CAB, so the format is
 * parsed here directly.
 *
 * <p>Supported folder compression types are NONE and MSZIP. MSZIP is deflate in 32 KiB blocks
 * where every block may reference the previous block's output as a preset dictionary, which is
 * modelled explicitly on both the decode and the encode side.
 *
 * <p>Like the ZIP codec, the file is treated as a partition: CFHEADER, CFFOLDER, CFFILE and
 * CFDATA headers are kept verbatim, and only the block payloads are re-encoded. Because
 * re-encoding a deflate stream is encoder specific, decomposition finishes with a re-encode of
 * every block compared against the original; a single mismatch makes the whole cabinet opaque.
 */
public final class CabCodec implements ContainerCodec {

    private static final int SIG = 0x4643534D; // 'MSCF' little endian
    private static final int FLAG_RESERVE_PRESENT = 0x0004;
    private static final int FLAG_PREV_CABINET = 0x0001;
    private static final int FLAG_NEXT_CABINET = 0x0002;

    private static final int COMPRESS_NONE = 0;
    private static final int COMPRESS_MSZIP = 1;

    private static final int MSZIP_BLOCK = 32768;
    private static final int INLINE_LIMIT = 4096;

    private static final byte SEG_INLINE = 0;
    private static final byte SEG_PART = 1;

    @Override
    public ContainerFormat format() {
        return ContainerFormat.CAB;
    }

    @Override
    public Decomposition decompose(ByteSource src, WorkDir wd, DecomposeLimits limits) throws IOException {
        try (RandomAccessSource ras = RandomAccessSource.open(src)) {
            Cabinet cab = parse(ras);
            if (cab == null) {
                return null;
            }

            ByteArrayOutputStream metaBuf = new ByteArrayOutputStream(1 << 14);
            DataOutputStream meta = new DataOutputStream(metaBuf);
            List<ByteSource> parts = new ArrayList<>();

            writeBytes(meta, ras.read(0, (int) cab.dataStart));
            meta.writeInt(cab.folders.size());

            for (Folder folder : cab.folders) {
                ByteSource plain = inflateFolder(ras, folder, wd);
                if (plain == null) {
                    return null;
                }
                int level = folder.compressType == COMPRESS_MSZIP
                        ? probeMszipLevel(ras, folder, plain)
                        : 0;
                if (level < 0) {
                    return null;
                }
                meta.writeByte(folder.compressType);
                meta.writeByte(level);
                meta.writeInt(folder.blocks.size());
                for (Block blk : folder.blocks) {
                    meta.writeInt(blk.cbData);
                    meta.writeInt(blk.cbUncomp);
                    writeBytes(meta, ras.read(blk.headerOffset, blk.headerLength));
                }
                writeFolderSegments(meta, parts, folder, plain, wd);
            }

            writeBytes(meta, ras.read(cab.dataEnd, (int) (ras.size() - cab.dataEnd)));
            meta.flush();
            return new Decomposition(metaBuf.toByteArray(), parts);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void rebuild(byte[] metaBytes, List<ByteSource> parts, OutputStream out, int threads)
            throws IOException {
        DataInputStream meta = new DataInputStream(new java.io.ByteArrayInputStream(metaBytes));
        out.write(readBytes(meta));
        int folderCount = meta.readInt();
        int partIndex = 0;
        for (int f = 0; f < folderCount; f++) {
            int compressType = meta.readUnsignedByte();
            int level = meta.readUnsignedByte();
            int blockCount = meta.readInt();
            int[] cbData = new int[blockCount];
            int[] cbUncomp = new int[blockCount];
            byte[][] headers = new byte[blockCount][];
            for (int i = 0; i < blockCount; i++) {
                cbData[i] = meta.readInt();
                cbUncomp[i] = meta.readInt();
                headers[i] = readBytes(meta);
            }
            List<ByteSource> folderParts = new ArrayList<>();
            List<byte[]> inlines = new ArrayList<>();
            List<Boolean> isPart = new ArrayList<>();
            int segCount = meta.readInt();
            for (int i = 0; i < segCount; i++) {
                if (meta.readUnsignedByte() == SEG_INLINE) {
                    inlines.add(readBytes(meta));
                    isPart.add(false);
                } else {
                    if (partIndex >= parts.size()) {
                        throw new IOException("blueprint references more CAB payloads than available");
                    }
                    folderParts.add(parts.get(partIndex++));
                    inlines.add(null);
                    isPart.add(true);
                }
            }
            try (InputStream plain = concat(inlines, isPart, folderParts)) {
                writeFolderBlocks(out, compressType, level, cbData, cbUncomp, headers, plain);
            }
        }
        out.write(readBytes(meta));
        if (partIndex != parts.size()) {
            throw new IOException("unused payloads in CAB rebuild: " + (parts.size() - partIndex));
        }
    }

    // ------------------------------------------------------------- decompose

    private void writeFolderSegments(DataOutputStream meta, List<ByteSource> parts,
                                     Folder folder, ByteSource plain, WorkDir wd) throws IOException {
        List<long[]> ranges = new ArrayList<>();
        for (FileEntry fe : folder.files) {
            ranges.add(new long[]{fe.offset, fe.size});
        }
        ranges.sort(Comparator.comparingLong(r -> r[0]));

        List<Object> segments = new ArrayList<>();
        try (RandomAccessSource pr = RandomAccessSource.open(plain)) {
            long pos = 0;
            for (long[] r : ranges) {
                long start = r[0];
                long len = r[1];
                if (start < pos || start + len > pr.size()) {
                    // Overlapping or out of range file ranges: fall back to one opaque segment.
                    segments.clear();
                    segments.add(pr.slice(0, pr.size()));
                    pos = pr.size();
                    break;
                }
                addSegment(segments, pr, pos, start - pos);
                if (len > 0) {
                    segments.add(pr.slice(start, len));
                }
                pos = start + len;
            }
            addSegment(segments, pr, pos, pr.size() - pos);
        }

        meta.writeInt(segments.size());
        for (Object seg : segments) {
            if (seg instanceof byte[] inline) {
                meta.writeByte(SEG_INLINE);
                writeBytes(meta, inline);
            } else {
                meta.writeByte(SEG_PART);
                parts.add((ByteSource) seg);
            }
        }
    }

    private static void addSegment(List<Object> segments, RandomAccessSource pr, long pos, long len)
            throws IOException {
        if (len <= 0) {
            return;
        }
        if (len <= INLINE_LIMIT) {
            segments.add(pr.read(pos, (int) len));
        } else {
            segments.add(pr.slice(pos, len));
        }
    }

    /** Decodes all CFDATA blocks of a folder into one continuous payload. */
    private ByteSource inflateFolder(RandomAccessSource ras, Folder folder, WorkDir wd) throws IOException {
        if (folder.compressType == COMPRESS_NONE) {
            return wd.spill(out -> {
                for (Block blk : folder.blocks) {
                    out.write(ras.read(blk.dataOffset, blk.cbData));
                }
            });
        }
        if (folder.compressType != COMPRESS_MSZIP) {
            return null;
        }
        try {
            return wd.spill(out -> {
                byte[][] history = new byte[1][];
                for (Block blk : folder.blocks) {
                    byte[] raw = ras.read(blk.dataOffset, blk.cbData);
                    byte[] plain = mszipInflate(raw, blk.cbUncomp, history[0]);
                    out.write(plain);
                    history[0] = plain;
                }
            });
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] mszipInflate(byte[] raw, int expected, byte[] history) throws IOException {
        if (raw.length < 2 || raw[0] != 'C' || raw[1] != 'K') {
            throw new IOException("not an MSZIP block");
        }
        Inflater inf = new Inflater(true);
        try {
            if (history != null && history.length > 0) {
                int keep = Math.min(history.length, MSZIP_BLOCK);
                inf.setDictionary(history, history.length - keep, keep);
            }
            inf.setInput(raw, 2, raw.length - 2);
            byte[] out = new byte[expected];
            int done = 0;
            while (done < expected) {
                int n = inf.inflate(out, done, expected - done);
                if (n == 0) {
                    if (inf.finished() || inf.needsInput()) {
                        break;
                    }
                    if (inf.needsDictionary()) {
                        throw new IOException("MSZIP block needs a dictionary we do not have");
                    }
                }
                done += n;
            }
            if (done != expected) {
                throw new IOException("MSZIP block short by " + (expected - done) + " bytes");
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("corrupt MSZIP block", e);
        } finally {
            inf.end();
        }
    }

    /** @return the deflate level that reproduces every block of the folder, or -1 */
    private int probeMszipLevel(RandomAccessSource ras, Folder folder, ByteSource plain) throws IOException {
        for (int level : DeflateSupport.LEVEL_ORDER) {
            if (mszipMatches(ras, folder, plain, level)) {
                return level;
            }
        }
        return -1;
    }

    private boolean mszipMatches(RandomAccessSource ras, Folder folder, ByteSource plain, int level)
            throws IOException {
        try (InputStream in = plain.openStream()) {
            byte[] history = null;
            for (Block blk : folder.blocks) {
                byte[] chunk = in.readNBytes(blk.cbUncomp);
                if (chunk.length != blk.cbUncomp) {
                    return false;
                }
                byte[] encoded = mszipDeflate(chunk, history, level);
                if (encoded.length != blk.cbData) {
                    return false;
                }
                if (!java.util.Arrays.equals(encoded, ras.read(blk.dataOffset, blk.cbData))) {
                    return false;
                }
                history = chunk;
            }
            return true;
        }
    }

    private static byte[] mszipDeflate(byte[] data, byte[] history, int level) throws IOException {
        Deflater def = new Deflater(level, true);
        try {
            if (history != null && history.length > 0) {
                int keep = Math.min(history.length, MSZIP_BLOCK);
                def.setDictionary(history, history.length - keep, keep);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 64);
            bos.write('C');
            bos.write('K');
            def.setInput(data);
            def.finish();
            byte[] buf = new byte[1 << 16];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n > 0) {
                    bos.write(buf, 0, n);
                } else if (def.needsInput()) {
                    break;
                }
            }
            return bos.toByteArray();
        } finally {
            def.end();
        }
    }

    // --------------------------------------------------------------- rebuild

    private static void writeFolderBlocks(OutputStream out, int compressType, int level,
                                          int[] cbData, int[] cbUncomp, byte[][] headers,
                                          InputStream plain) throws IOException {
        byte[] history = null;
        for (int i = 0; i < cbData.length; i++) {
            byte[] chunk = plain.readNBytes(cbUncomp[i]);
            if (chunk.length != cbUncomp[i]) {
                throw new IOException("CAB folder payload shorter than the blueprint expects");
            }
            out.write(headers[i]);
            if (compressType == COMPRESS_NONE) {
                out.write(chunk);
            } else {
                byte[] encoded = mszipDeflate(chunk, history, level);
                if (encoded.length != cbData[i]) {
                    throw new IOException("MSZIP re-encode changed size: " + encoded.length + " != " + cbData[i]);
                }
                out.write(encoded);
                history = chunk;
            }
        }
    }

    private static InputStream concat(List<byte[]> inlines, List<Boolean> isPart, List<ByteSource> parts)
            throws IOException {
        List<InputStream> streams = new ArrayList<>(inlines.size());
        int p = 0;
        for (int i = 0; i < inlines.size(); i++) {
            if (isPart.get(i)) {
                streams.add(parts.get(p++).openStream());
            } else {
                streams.add(new java.io.ByteArrayInputStream(inlines.get(i)));
            }
        }
        return new SequenceInputStream(Collections.enumeration(streams));
    }

    // --------------------------------------------------------------- parsing

    private static final class Cabinet {
        long dataStart;
        long dataEnd;
        List<Folder> folders = new ArrayList<>();
    }

    private static final class Folder {
        int compressType;
        List<Block> blocks = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
    }

    private static final class Block {
        long headerOffset;
        int headerLength;
        long dataOffset;
        int cbData;
        int cbUncomp;
    }

    private static final class FileEntry {
        long offset;
        long size;
    }

    private static Cabinet parse(RandomAccessSource ras) throws IOException {
        if (ras.size() < 36) {
            return null;
        }
        ByteBuffer h = ByteBuffer.wrap(ras.read(0, 36)).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt(0) != SIG) {
            return null;
        }
        long cbCabinet = u32(h, 8);
        long coffFiles = u32(h, 16);
        int folderCount = u16(h, 26);
        int fileCount = u16(h, 28);
        int flags = u16(h, 30);
        if (cbCabinet != ras.size() || folderCount == 0) {
            return null;
        }
        if ((flags & (FLAG_PREV_CABINET | FLAG_NEXT_CABINET)) != 0) {
            // Multi part cabinets split folder data across files; not handled.
            return null;
        }

        int cbCFFolder = 0;
        int cbCFData = 0;
        long p = 36;
        if ((flags & FLAG_RESERVE_PRESENT) != 0) {
            ByteBuffer r = ByteBuffer.wrap(ras.read(p, 4)).order(ByteOrder.LITTLE_ENDIAN);
            int cbCFHeader = u16(r, 0);
            cbCFFolder = r.get(2) & 0xFF;
            cbCFData = r.get(3) & 0xFF;
            p += 4 + cbCFHeader;
        }

        Cabinet cab = new Cabinet();
        long[] folderStarts = new long[folderCount];
        int[] folderBlockCounts = new int[folderCount];
        for (int i = 0; i < folderCount; i++) {
            ByteBuffer f = ByteBuffer.wrap(ras.read(p, 8)).order(ByteOrder.LITTLE_ENDIAN);
            folderStarts[i] = u32(f, 0);
            folderBlockCounts[i] = u16(f, 4);
            Folder folder = new Folder();
            folder.compressType = u16(f, 6) & 0x000F;
            if (folder.compressType != COMPRESS_NONE && folder.compressType != COMPRESS_MSZIP) {
                return null;
            }
            cab.folders.add(folder);
            p += 8 + cbCFFolder;
        }

        p = coffFiles;
        for (int i = 0; i < fileCount; i++) {
            ByteBuffer f = ByteBuffer.wrap(ras.read(p, 16)).order(ByteOrder.LITTLE_ENDIAN);
            long cbFile = u32(f, 0);
            long uoff = u32(f, 4);
            int iFolder = u16(f, 8);
            long nameStart = p + 16;
            long nameLen = 0;
            while (ras.read(nameStart + nameLen, 1)[0] != 0) {
                nameLen++;
                if (nameStart + nameLen >= ras.size()) {
                    return null;
                }
            }
            if (iFolder < cab.folders.size()) {
                FileEntry fe = new FileEntry();
                fe.offset = uoff;
                fe.size = cbFile;
                cab.folders.get(iFolder).files.add(fe);
            }
            p = nameStart + nameLen + 1;
        }

        // Folder data must follow the headers contiguously, in folder order.
        long cursor = -1;
        for (int i = 0; i < folderCount; i++) {
            Folder folder = cab.folders.get(i);
            long blockPos = folderStarts[i];
            if (cursor >= 0 && blockPos != cursor) {
                return null;
            }
            if (cursor < 0) {
                cab.dataStart = blockPos;
            }
            for (int b = 0; b < folderBlockCounts[i]; b++) {
                ByteBuffer d = ByteBuffer.wrap(ras.read(blockPos, 8)).order(ByteOrder.LITTLE_ENDIAN);
                Block blk = new Block();
                blk.headerOffset = blockPos;
                blk.headerLength = 8 + cbCFData;
                blk.cbData = u16(d, 4);
                blk.cbUncomp = u16(d, 6);
                blk.dataOffset = blockPos + blk.headerLength;
                if (blk.dataOffset + blk.cbData > ras.size()) {
                    return null;
                }
                folder.blocks.add(blk);
                blockPos = blk.dataOffset + blk.cbData;
            }
            cursor = blockPos;
        }
        cab.dataEnd = cursor;
        if (cab.dataStart <= 0 || cab.dataEnd > ras.size()) {
            return null;
        }
        return cab;
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        return in.readNBytes(in.readInt());
    }

    private static int u16(ByteBuffer b, int pos) {
        return b.getShort(pos) & 0xFFFF;
    }

    private static long u32(ByteBuffer b, int pos) {
        return b.getInt(pos) & 0xFFFFFFFFL;
    }
}
