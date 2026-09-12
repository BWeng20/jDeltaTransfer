package com.bw.jdt.core;

import com.bw.jdt.core.format.ContainerCodec;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the <em>content</em> of an archive somewhere else, instead of reproducing the original
 * bytes.
 *
 * <p>This exists for one reason: 7z packs its entries into a single solid LZMA2 stream, so
 * rebuilding a 7z is strictly sequential and costs minutes per gigabyte, on every transfer. If
 * the consumer does not actually need the original container, writing the same content into a
 * stored ZIP -- or straight onto disk -- turns that into plain I/O.
 *
 * <p>It is a different promise, and the difference matters: the result does <strong>not</strong>
 * have the archive's SHA-256, because it is not that file. What is still guaranteed is that every
 * byte came from a block that was hash verified on arrival, and that each entry was written with
 * exactly the content the blueprint describes, which {@link #verifyEntries} checks per entry.
 *
 * <p>Nested archives are kept as they are: each is rebuilt bit exactly and stored as one member.
 * Only the outermost container changes shape.
 */
public final class Repacker {

    /** What to produce instead of the original container. */
    public enum Mode {
        /** A ZIP with stored (uncompressed) entries. Recompressing would defeat the purpose. */
        ZIP,
        /** The entries written into a directory tree. */
        EXTRACT
    }

    private final ChunkSource chunks;
    private final WorkDir wd;
    private int threads = 1;

    public Repacker(ChunkSource chunks, WorkDir wd) {
        this.chunks = chunks;
        this.wd = wd;
    }

    public Repacker withThreads(int threads) {
        this.threads = Math.max(1, threads);
        return this;
    }

    /** Outcome of a repack, so the caller can report what it actually produced. */
    public record Result(Mode mode, int entries, long contentBytes, long outputBytes) {
    }

    /**
     * @return {@code true} if {@code bp} can be repacked, i.e. its outermost container is one the
     *         codec can describe in logical terms
     */
    public static boolean isSupported(Blueprint bp) throws IOException {
        return plan(bp) != null;
    }

    /** Writes the archive's content and returns what was produced. */
    public Result write(Blueprint bp, Mode mode, Path target) throws IOException {
        List<Member> plan = plan(bp);
        if (plan == null) {
            throw new IOException("the outermost container of this version cannot be described "
                    + "in logical terms; rebuild it in its original format instead");
        }
        return switch (mode) {
            case ZIP -> writeZip(plan, target);
            case EXTRACT -> extract(plan, target);
        };
    }

    // ------------------------------------------------------------------ plan

    /** One member of the outer container, paired with the node that carries its content. */
    private record Member(String name, Node content) {
    }

    private static List<Member> plan(Blueprint bp) throws IOException {
        if (!(bp.root() instanceof Node.Container root)) {
            // An opaque archive has no members to speak of.
            return null;
        }
        List<ContainerCodec.LogicalEntry> listing = Codecs.of(root.format()).list(root.meta());
        if (listing == null || listing.isEmpty()) {
            return null;
        }
        List<Member> members = new ArrayList<>(listing.size());
        for (ContainerCodec.LogicalEntry e : listing) {
            if (e.partIndex() < 0 || e.partIndex() >= root.children().size()) {
                return null;
            }
            String name = sanitise(e.name());
            if (name == null) {
                return null;
            }
            members.add(new Member(name, root.children().get(e.partIndex())));
        }
        return members;
    }

    /**
     * Rejects absolute paths and {@code ..} segments. Archive member names are attacker
     * controlled as far as this code is concerned, and {@link Mode#EXTRACT} writes them to disk.
     */
    private static String sanitise(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains(":") || n.endsWith("/")) {
            return null;
        }
        for (String segment : n.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                return null;
            }
        }
        return n;
    }

    // ----------------------------------------------------------------- write

    private Result writeZip(List<Member> plan, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        long content = 0;
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target), 1 << 20))) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Member m : plan) {
                ByteSource src = materialise(m.content());
                ZipEntry entry = new ZipEntry(m.name());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(src.size());
                entry.setCompressedSize(src.size());
                entry.setCrc(crc32(src));
                zip.putNextEntry(entry);
                content += copy(src, zip);
                zip.closeEntry();
            }
        }
        return new Result(Mode.ZIP, plan.size(), content, Files.size(target));
    }

    private Result extract(List<Member> plan, Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long content = 0;
        long written = 0;
        for (Member m : plan) {
            Path file = root.resolve(m.name()).normalize();
            if (!file.startsWith(root)) {
                throw new IOException("member would escape the output directory: " + m.name());
            }
            Files.createDirectories(file.getParent());
            ByteSource src = materialise(m.content());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
                content += copy(src, out);
            }
            written += Files.size(file);
        }
        return new Result(Mode.EXTRACT, plan.size(), content, written);
    }

    /**
     * Checks every member against the content the blueprint describes for it. Catches a writer
     * that reorders, truncates or duplicates, which is what a hand written packer gets wrong.
     */
    public void verifyEntries(Blueprint bp, Mode mode, Path target) throws IOException {
        List<Member> plan = plan(bp);
        if (plan == null) {
            throw new IOException("nothing to verify: this version cannot be repacked");
        }
        for (Member m : plan) {
            Hash expected = Hashes.of(materialise(m.content()));
            Hash actual = switch (mode) {
                case ZIP -> hashZipEntry(target, m.name());
                case EXTRACT -> Hashes.ofFile(target.resolve(m.name()));
            };
            if (!expected.equals(actual)) {
                throw new IOException("repacked member '" + m.name() + "' does not match the "
                        + "blueprint: expected " + expected + ", got " + actual);
            }
        }
    }

    private static Hash hashZipEntry(Path zip, String name) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            ZipEntry entry = zf.getEntry(name);
            if (entry == null) {
                throw new IOException("repacked ZIP is missing member '" + name + "'");
            }
            try (InputStream in = zf.getInputStream(entry)) {
                return Hashes.of(in);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * Leaf payloads stream straight out of the block store; a nested archive is rebuilt bit
     * exactly into a temp file first, because it stays a single member of the output.
     */
    private ByteSource materialise(Node node) throws IOException {
        if (node instanceof Node.Blob b) {
            return new ChunkedByteSource(b.chunks(), b.size(), chunks);
        }
        return wd.spill(out -> new Reassembler(chunks, wd).withThreads(threads).write(node, out));
    }

    private static long copy(ByteSource src, OutputStream out) throws IOException {
        try (InputStream in = src.openStream()) {
            return in.transferTo(out);
        }
    }

    private static long crc32(ByteSource src) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = src.openStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }
}
