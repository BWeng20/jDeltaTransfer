package com.bw.jdt.core.format;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ContainerFormat;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.core.WorkDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 7z codec on top of Apache Commons Compress.
 *
 * <p>7z stores entries in solid LZMA streams, so an opaque 7z inside an archive would defeat
 * delta transfer completely: changing one byte rewrites the whole stream. Decomposing it into
 * per entry payloads is what recovers the delta.
 *
 * <p>Unlike ZIP and CAB, a 7z container cannot be re-encoded byte for byte by construction --
 * the encoder settings are not fully recoverable from the file. Decomposition therefore ends
 * with a real round trip: the container is rebuilt and its SHA-256 compared with the original.
 * Only an exact match is accepted; otherwise the 7z stays an opaque blob.
 */
public final class SevenZCodec implements ContainerCodec {

    private static final SevenZMethod[] CANDIDATE_METHODS = {
            SevenZMethod.LZMA2, SevenZMethod.DEFLATE, SevenZMethod.COPY, SevenZMethod.BZIP2, SevenZMethod.LZMA
    };

    /** Above this size the round trip verification costs more than the delta is worth. */
    private static final long MAX_DECOMPOSE_SIZE = 512L * 1024 * 1024;

    @Override
    public ContainerFormat format() {
        return ContainerFormat.SEVEN_Z;
    }

    @Override
    public Decomposition decompose(ByteSource src, WorkDir wd) throws IOException {
        if (src.size() > MAX_DECOMPOSE_SIZE) {
            return null;
        }
        Path local = materialise(src, wd);
        List<EntryMeta> entries = new ArrayList<>();
        List<ByteSource> parts = new ArrayList<>();
        try (SevenZFile sz = SevenZFile.builder().setPath(local).get()) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                EntryMeta m = EntryMeta.from(e);
                entries.add(m);
                if (m.hasStream) {
                    final SevenZFile cur = sz;
                    parts.add(wd.spill(out -> copyEntry(cur, out)));
                }
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        if (entries.isEmpty()) {
            return null;
        }

        // Find the encoder settings that reproduce the original bytes, if any.
        com.bw.jdt.core.Hash want = Hashes.of(src);
        for (SevenZMethod method : candidateOrder(entries)) {
            byte[] meta = encodeMeta(entries, method);
            try {
                Path probe = wd.newFile(".7z");
                try (OutputStream out = Files.newOutputStream(probe)) {
                    rebuild(meta, parts, out);
                }
                if (Hashes.ofFile(probe).equals(want)) {
                    Files.deleteIfExists(probe);
                    return new Decomposition(meta, parts);
                }
                Files.deleteIfExists(probe);
            } catch (IOException | RuntimeException ignored) {
                // Try the next method.
            }
        }
        return null;
    }

    @Override
    public void rebuild(byte[] metaBytes, List<ByteSource> parts, OutputStream out) throws IOException {
        DataInputStream meta = new DataInputStream(new java.io.ByteArrayInputStream(metaBytes));
        SevenZMethod method = SevenZMethod.valueOf(meta.readUTF());
        int count = meta.readInt();
        List<EntryMeta> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(EntryMeta.read(meta));
        }

        Path tmp = Files.createTempFile("jdt-7z-rebuild", ".7z");
        try {
            Files.deleteIfExists(tmp);
            try (SevenZOutputFile sz = new SevenZOutputFile(tmp.toFile())) {
                sz.setContentMethods(Collections.singletonList(new SevenZMethodConfiguration(method)));
                int partIndex = 0;
                for (EntryMeta m : entries) {
                    SevenZArchiveEntry e = m.toEntry();
                    sz.putArchiveEntry(e);
                    if (m.hasStream) {
                        if (partIndex >= parts.size()) {
                            throw new IOException("blueprint references more 7z payloads than available");
                        }
                        try (InputStream in = parts.get(partIndex++).openStream()) {
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                sz.write(buf, 0, n);
                            }
                        }
                    }
                    sz.closeArchiveEntry();
                }
                sz.finish();
                if (partIndex != parts.size()) {
                    throw new IOException("unused payloads in 7z rebuild: " + (parts.size() - partIndex));
                }
            }
            try (InputStream in = Files.newInputStream(tmp)) {
                in.transferTo(out);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void copyEntry(SevenZFile sz, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = sz.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static Path materialise(ByteSource src, WorkDir wd) throws IOException {
        if (src instanceof ByteSource.FileByteSource f && f.offset() == 0
                && f.size() == Files.size(f.file())) {
            return f.file();
        }
        Path p = wd.newFile(".7z");
        try (OutputStream out = Files.newOutputStream(p)) {
            src.copyTo(out);
        }
        return p;
    }

    /** Puts the method the file itself advertises first, then the usual suspects. */
    private static List<SevenZMethod> candidateOrder(List<EntryMeta> entries) {
        List<SevenZMethod> order = new ArrayList<>();
        for (EntryMeta m : entries) {
            if (m.method != null) {
                try {
                    SevenZMethod declared = SevenZMethod.valueOf(m.method);
                    if (!order.contains(declared)) {
                        order.add(declared);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unknown method name, fall through to the defaults.
                }
            }
        }
        for (SevenZMethod m : CANDIDATE_METHODS) {
            if (!order.contains(m)) {
                order.add(m);
            }
        }
        return order;
    }

    private static byte[] encodeMeta(List<EntryMeta> entries, SevenZMethod method) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 12);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeUTF(method.name());
        out.writeInt(entries.size());
        for (EntryMeta m : entries) {
            m.write(out);
        }
        out.flush();
        return bos.toByteArray();
    }

    /** The subset of 7z entry attributes needed to rebuild the container. */
    private static final class EntryMeta {
        String name;
        boolean directory;
        boolean antiItem;
        boolean hasStream;
        long size;
        boolean hasLastModified;
        long lastModified;
        boolean hasCreation;
        long creation;
        boolean hasAccess;
        long access;
        boolean hasAttributes;
        int attributes;
        String method;

        static EntryMeta from(SevenZArchiveEntry e) {
            EntryMeta m = new EntryMeta();
            m.name = e.getName() == null ? "" : e.getName();
            m.directory = e.isDirectory();
            m.antiItem = e.isAntiItem();
            m.hasStream = e.hasStream();
            m.size = e.getSize();
            m.hasLastModified = e.getHasLastModifiedDate();
            m.lastModified = m.hasLastModified ? e.getLastModifiedDate().getTime() : 0;
            m.hasCreation = e.getHasCreationDate();
            m.creation = m.hasCreation ? e.getCreationDate().getTime() : 0;
            m.hasAccess = e.getHasAccessDate();
            m.access = m.hasAccess ? e.getAccessDate().getTime() : 0;
            m.hasAttributes = e.getHasWindowsAttributes();
            m.attributes = m.hasAttributes ? e.getWindowsAttributes() : 0;
            Iterable<? extends SevenZMethodConfiguration> methods = e.getContentMethods();
            if (methods != null) {
                for (SevenZMethodConfiguration c : methods) {
                    if (c.getMethod() != null) {
                        m.method = c.getMethod().name();
                        break;
                    }
                }
            }
            return m;
        }

        SevenZArchiveEntry toEntry() {
            SevenZArchiveEntry e = new SevenZArchiveEntry();
            e.setName(name);
            e.setDirectory(directory);
            e.setAntiItem(antiItem);
            e.setHasStream(hasStream);
            e.setSize(size);
            e.setHasLastModifiedDate(hasLastModified);
            if (hasLastModified) {
                e.setLastModifiedDate(new java.util.Date(lastModified));
            }
            e.setHasCreationDate(hasCreation);
            if (hasCreation) {
                e.setCreationDate(new java.util.Date(creation));
            }
            e.setHasAccessDate(hasAccess);
            if (hasAccess) {
                e.setAccessDate(new java.util.Date(access));
            }
            e.setHasWindowsAttributes(hasAttributes);
            if (hasAttributes) {
                e.setWindowsAttributes(attributes);
            }
            return e;
        }

        void write(DataOutputStream out) throws IOException {
            out.writeUTF(name);
            out.writeBoolean(directory);
            out.writeBoolean(antiItem);
            out.writeBoolean(hasStream);
            out.writeLong(size);
            out.writeBoolean(hasLastModified);
            out.writeLong(lastModified);
            out.writeBoolean(hasCreation);
            out.writeLong(creation);
            out.writeBoolean(hasAccess);
            out.writeLong(access);
            out.writeBoolean(hasAttributes);
            out.writeInt(attributes);
            out.writeUTF(method == null ? "" : method);
        }

        static EntryMeta read(DataInputStream in) throws IOException {
            EntryMeta m = new EntryMeta();
            m.name = in.readUTF();
            m.directory = in.readBoolean();
            m.antiItem = in.readBoolean();
            m.hasStream = in.readBoolean();
            m.size = in.readLong();
            m.hasLastModified = in.readBoolean();
            m.lastModified = in.readLong();
            m.hasCreation = in.readBoolean();
            m.creation = in.readLong();
            m.hasAccess = in.readBoolean();
            m.access = in.readLong();
            m.hasAttributes = in.readBoolean();
            m.attributes = in.readInt();
            String method = in.readUTF();
            m.method = method.isEmpty() ? null : method;
            return m;
        }
    }
}
