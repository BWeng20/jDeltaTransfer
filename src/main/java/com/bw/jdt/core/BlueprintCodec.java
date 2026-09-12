package com.bw.jdt.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary (gzip wrapped) serialisation of a {@link Blueprint}. Compact on purpose: for a 1 GiB
 * archive the blueprint is a few hundred kilobytes and is sent on every delta transfer.
 */
public final class BlueprintCodec {

    private static final byte[] MAGIC = {'J', 'D', 'T', 'B', 'P'};
    private static final int FORMAT_VERSION = 1;

    private static final byte NODE_BLOB = 0;
    private static final byte NODE_CONTAINER = 1;

    private BlueprintCodec() {
    }

    public static byte[] toGzipBytes(Blueprint bp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 1 << 16)) {
            write(bp, gz);
        }
        return bos.toByteArray();
    }

    public static Blueprint fromGzipBytes(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data), 1 << 16)) {
            return read(gz);
        }
    }

    public static void write(Blueprint bp, OutputStream raw) throws IOException {
        DataOutputStream out = new DataOutputStream(raw);
        out.write(MAGIC);
        out.writeByte(FORMAT_VERSION);
        out.write(bp.archiveHash().toBytes());
        out.writeLong(bp.archiveSize());
        out.writeInt(bp.chunkParams().min());
        out.writeInt(bp.chunkParams().avg());
        out.writeInt(bp.chunkParams().max());
        writeNode(out, bp.root());
        out.flush();
    }

    public static Blueprint read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = in.readNBytes(MAGIC.length);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("not a jDeltaTransfer blueprint");
        }
        int version = in.readUnsignedByte();
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported blueprint version " + version);
        }
        Hash hash = Hash.wrap(in.readNBytes(Hash.LENGTH));
        long size = in.readLong();
        Chunker.Params params = new Chunker.Params(in.readInt(), in.readInt(), in.readInt());
        Node root = readNode(in);
        return new Blueprint(hash, size, params, root);
    }

    private static void writeNode(DataOutputStream out, Node node) throws IOException {
        if (node instanceof Node.Blob b) {
            out.writeByte(NODE_BLOB);
            out.writeLong(b.size());
            out.writeInt(b.chunks().size());
            for (ChunkRef c : b.chunks()) {
                out.write(c.hash().toBytes());
                out.writeInt(c.length());
            }
        } else if (node instanceof Node.Container c) {
            out.writeByte(NODE_CONTAINER);
            out.writeByte(c.format().id());
            out.writeLong(c.size());
            out.writeInt(c.meta().length);
            out.write(c.meta());
            out.writeInt(c.children().size());
            for (Node child : c.children()) {
                writeNode(out, child);
            }
        } else {
            throw new IOException("unknown node type " + node.getClass());
        }
    }

    private static Node readNode(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        switch (type) {
            case NODE_BLOB -> {
                long size = in.readLong();
                int n = in.readInt();
                List<ChunkRef> chunks = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    chunks.add(new ChunkRef(Hash.wrap(in.readNBytes(Hash.LENGTH)), in.readInt()));
                }
                return new Node.Blob(size, chunks);
            }
            case NODE_CONTAINER -> {
                ContainerFormat fmt = ContainerFormat.byId(in.readUnsignedByte());
                long size = in.readLong();
                byte[] meta = in.readNBytes(in.readInt());
                int n = in.readInt();
                List<Node> children = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    children.add(readNode(in));
                }
                return new Node.Container(fmt, meta, children, size);
            }
            default -> throw new IOException("unknown node tag " + type);
        }
    }
}
