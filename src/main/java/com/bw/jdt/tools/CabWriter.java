package com.bw.jdt.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Writes single folder MSZIP cabinets.
 *
 * <p>There is no CAB writer in the JDK or in Commons Compress, and the test data needs real
 * nested cabinets, so this produces them directly: CFHEADER, one CFFOLDER, the CFFILE table and
 * MSZIP compressed CFDATA blocks of at most 32 KiB uncompressed each, every block after the
 * first using its predecessor's output as preset dictionary.
 */
public final class CabWriter {

    private static final int MSZIP_BLOCK = 32768;

    /** One file to put into the cabinet. */
    public interface Entry {
        String name();

        long size();

        InputStream open() throws IOException;
    }

    private final int deflateLevel;

    public CabWriter(int deflateLevel) {
        this.deflateLevel = deflateLevel;
    }

    public void write(Path target, List<Entry> entries) throws IOException {
        long headerSize = 36 + 8L; // CFHEADER + one CFFOLDER
        long fileTableSize = 0;
        for (Entry e : entries) {
            fileTableSize += 16 + e.name().getBytes(StandardCharsets.US_ASCII).length + 1;
        }
        long coffFiles = headerSize;
        long dataStart = headerSize + fileTableSize;

        Path blocksTmp = target.resolveSibling(target.getFileName() + ".blocks");
        List<int[]> blocks = new ArrayList<>();
        long totalUncompressed = 0;
        try {
            try (OutputStream blockOut = new java.io.BufferedOutputStream(
                    Files.newOutputStream(blocksTmp), 1 << 20);
                 InputStream in = concat(entries)) {
                byte[] chunk = new byte[MSZIP_BLOCK];
                byte[] history = null;
                while (true) {
                    int n = in.readNBytes(chunk, 0, MSZIP_BLOCK);
                    if (n == 0) {
                        break;
                    }
                    byte[] payload = mszipDeflate(chunk, n, history, deflateLevel);
                    if (payload.length > 0xFFFF) {
                        throw new IOException("MSZIP block larger than a CFDATA can hold");
                    }
                    byte[] header = new byte[8];
                    ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    hb.putInt(0, 0);
                    hb.putShort(4, (short) payload.length);
                    hb.putShort(6, (short) n);
                    hb.putInt(0, checksum(header, 4, 4, checksum(payload, 0, payload.length, 0)));
                    blockOut.write(header);
                    blockOut.write(payload);
                    blocks.add(new int[]{payload.length, n});
                    totalUncompressed += n;
                    history = n == MSZIP_BLOCK ? chunk.clone() : java.util.Arrays.copyOf(chunk, n);
                    if (n < MSZIP_BLOCK) {
                        break;
                    }
                }
            }
            if (blocks.size() > 0xFFFF) {
                throw new IOException("cabinet needs more than 65535 data blocks");
            }

            long dataSize = Files.size(blocksTmp);
            long cbCabinet = dataStart + dataSize;
            if (cbCabinet > 0xFFFFFFFFL) {
                throw new IOException("cabinet larger than 4 GiB");
            }

            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 20)) {
                ByteBuffer h = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
                h.put(new byte[]{'M', 'S', 'C', 'F'});
                h.putInt(0);                      // reserved1
                h.putInt((int) cbCabinet);        // cbCabinet
                h.putInt(0);                      // reserved2
                h.putInt((int) coffFiles);        // coffFiles
                h.putInt(0);                      // reserved3
                h.put((byte) 3).put((byte) 1);    // version 1.3
                h.putShort((short) 1);            // cFolders
                h.putShort((short) entries.size());
                h.putShort((short) 0);            // flags
                h.putShort((short) 0x1234);       // setID
                h.putShort((short) 0);            // iCabinet
                out.write(h.array());

                ByteBuffer f = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                f.putInt((int) dataStart);        // coffCabStart
                f.putShort((short) blocks.size());
                f.putShort((short) 1);            // typeCompress = MSZIP
                out.write(f.array());

                long offset = 0;
                for (Entry e : entries) {
                    byte[] name = e.name().getBytes(StandardCharsets.US_ASCII);
                    ByteBuffer c = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    c.putInt((int) e.size());
                    c.putInt((int) offset);
                    c.putShort((short) 0);        // iFolder
                    c.putShort((short) 0x5679);   // date
                    c.putShort((short) 0x1234);   // time
                    c.putShort((short) 0x20);     // attribs: archive
                    out.write(c.array());
                    out.write(name);
                    out.write(0);
                    offset += e.size();
                }
                if (offset != totalUncompressed) {
                    throw new IOException("cabinet file table does not match the payload size");
                }
                try (InputStream in = Files.newInputStream(blocksTmp)) {
                    in.transferTo(out);
                }
            }
        } finally {
            Files.deleteIfExists(blocksTmp);
        }
    }

    private static byte[] mszipDeflate(byte[] data, int len, byte[] history, int level) {
        Deflater def = new Deflater(level, true);
        try {
            if (history != null && history.length > 0) {
                int keep = Math.min(history.length, MSZIP_BLOCK);
                def.setDictionary(history, history.length - keep, keep);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(len / 2 + 64);
            bos.write('C');
            bos.write('K');
            def.setInput(data, 0, len);
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

    /** The checksum algorithm from the cabinet specification. */
    static int checksum(byte[] data, int off, int len, int seed) {
        int ul = seed;
        int i = off;
        int cb = len;
        while (cb >= 4) {
            ul ^= (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8)
                    | ((data[i + 2] & 0xFF) << 16) | ((data[i + 3] & 0xFF) << 24);
            i += 4;
            cb -= 4;
        }
        int tail = 0;
        switch (cb) {
            case 3 -> tail = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            case 2 -> tail = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            case 1 -> tail = data[i] & 0xFF;
            default -> {
            }
        }
        return ul ^ tail;
    }

    private static InputStream concat(List<Entry> entries) throws IOException {
        List<InputStream> streams = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            streams.add(e.open());
        }
        return new java.io.SequenceInputStream(java.util.Collections.enumeration(streams));
    }
}
