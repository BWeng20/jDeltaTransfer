package com.bw.jdt.core;

import com.bw.jdt.tools.CabWriter;
import com.bw.jdt.tools.Content;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The central correctness property: decomposing an archive and rebuilding it from its blocks
 * must reproduce the original file byte for byte.
 */
class RoundTripTest {

    private static Path generate(Path dir, int count, String size) throws Exception {
        GenerateTestArchives.main(new String[]{
                "--out", dir.toString(), "--count", String.valueOf(count),
                "--start-size", size, "--growth", "0.1", "--threads", "2"});
        return dir;
    }

    private static long countNodes(Node node, java.util.function.Predicate<Node> p) {
        long n = p.test(node) ? 1 : 0;
        if (node instanceof Node.Container c) {
            for (Node child : c.children()) {
                n += countNodes(child, p);
            }
        }
        return n;
    }

    @Test
    void generatedArchiveRebuildsExactly(@TempDir Path tmp) throws Exception {
        Path archives = generate(tmp.resolve("archives"), 1, "12MB");
        Path archive = archives.resolve("archive-v01.zip");
        assertTrue(Files.exists(archive));

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"));
             WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "d-")) {
            Blueprint bp = new Decomposer(store, Chunker.Params.of(65536, 4 << 20))
                    .decompose(ByteSource.ofFile(archive), wd);

            assertEquals(Hashes.ofFile(archive), bp.archiveHash());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WorkDir rwd = WorkDir.createTemp(tmp.resolve("work"), "r-")) {
                new Reassembler(store, rwd).writeVerified(bp, out);
            }
            assertArrayEqualsFile(archive, out.toByteArray());
        }
    }

    @Test
    void nestedZipCabAndSevenZAreOpenedNotTreatedAsOpaqueBytes(@TempDir Path tmp) throws Exception {
        Path archives = generate(tmp.resolve("archives"), 1, "12MB");
        Path archive = archives.resolve("archive-v01.zip");

        List<ContainerFormat> seen = new ArrayList<>();
        List<ContainerFormat> opaque = new ArrayList<>();
        Decomposer.Listener listener = new Decomposer.Listener() {
            @Override
            public void onContainer(ContainerFormat format, int depth, long size) {
                seen.add(format);
            }

            @Override
            public void onOpaque(ContainerFormat attempted, int depth, long size) {
                opaque.add(attempted);
            }
        };

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"));
             WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "d-")) {
            Blueprint bp = new Decomposer(store, Chunker.Params.of(65536, 4 << 20),
                    Decomposer.DEFAULT_MAX_DEPTH, listener).decompose(ByteSource.ofFile(archive), wd);

            assertTrue(seen.contains(ContainerFormat.ZIP), "outer and nested ZIPs must be opened");
            assertTrue(seen.contains(ContainerFormat.CAB), "nested CABs must be opened, saw " + seen);
            assertTrue(seen.contains(ContainerFormat.SEVEN_Z), "nested 7z must be opened, saw " + seen);
            assertTrue(opaque.isEmpty(), "nothing should fall back to opaque: " + opaque);

            long containers = countNodes(bp.root(), n -> n instanceof Node.Container);
            assertTrue(containers >= 8, "expected the nested archives as containers, got " + containers);
        }
    }

    /**
     * Two consecutive versions must share the overwhelming majority of their blocks; that share
     * is exactly what the delta transfer saves.
     */
    @Test
    void consecutiveVersionsShareMostBlocks(@TempDir Path tmp) throws Exception {
        Path archives = generate(tmp.resolve("archives"), 2, "12MB");

        try (ChunkStore storeA = ChunkStore.open(tmp.resolve("a"));
             ChunkStore storeB = ChunkStore.open(tmp.resolve("b"));
             WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "d-")) {
            Chunker.Params params = Chunker.Params.of(65536, 4 << 20);
            Blueprint a = new Decomposer(storeA, params)
                    .decompose(ByteSource.ofFile(archives.resolve("archive-v01.zip")), wd);
            Blueprint b = new Decomposer(storeB, params)
                    .decompose(ByteSource.ofFile(archives.resolve("archive-v02.zip")), wd);

            var needed = b.distinctChunks();
            long reusable = needed.keySet().stream().filter(storeA::contains).count();
            long reusableBytes = needed.entrySet().stream()
                    .filter(e -> storeA.contains(e.getKey()))
                    .mapToLong(java.util.Map.Entry::getValue).sum();
            long totalBytes = needed.values().stream().mapToLong(Integer::longValue).sum();

            assertTrue(reusable > 0, "no block reuse at all");
            double byteShare = (double) reusableBytes / totalBytes;
            assertTrue(byteShare > 0.5,
                    "expected the bulk of v2 to be derivable from v1, reuse was "
                            + Math.round(byteShare * 100) + "%");
        }
    }

    @Test
    void cabinetsWrittenHereRoundTripExactly(@TempDir Path tmp) throws Exception {
        Path cab = tmp.resolve("test.cab");
        List<CabWriter.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final long id = 100 + i;
            final long size = 300_000 + i * 77_777L;
            final Content.Kind kind = i % 2 == 0 ? Content.Kind.TEXT : Content.Kind.BINARY;
            entries.add(new CabWriter.Entry() {
                @Override
                public String name() {
                    return "file" + id + ".dat";
                }

                @Override
                public long size() {
                    return size;
                }

                @Override
                public InputStream open() {
                    return Content.open(id, 0, size, kind);
                }
            });
        }
        new CabWriter(6).write(cab, entries);

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"));
             WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "d-")) {
            Blueprint bp = new Decomposer(store, Chunker.Params.of(16384, 1 << 20))
                    .decompose(ByteSource.ofFile(cab), wd);

            assertTrue(bp.root() instanceof Node.Container c && c.format() == ContainerFormat.CAB,
                    "the cabinet must be recognised, got " + bp.root().getClass().getSimpleName());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WorkDir rwd = WorkDir.createTemp(tmp.resolve("work"), "r-")) {
                new Reassembler(store, rwd).writeVerified(bp, out);
            }
            assertArrayEqualsFile(cab, out.toByteArray());
        }
    }

    @Test
    void truncatedOrForeignDataStillRoundTripsAsOpaqueBlocks(@TempDir Path tmp) throws Exception {
        // A file that only looks like a ZIP must not break anything; it becomes plain blocks.
        Path fake = tmp.resolve("fake.zip");
        byte[] data = new byte[500_000];
        new java.util.Random(5).nextBytes(data);
        data[0] = 'P';
        data[1] = 'K';
        data[2] = 3;
        data[3] = 4;
        Files.write(fake, data);

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"));
             WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "d-")) {
            Blueprint bp = new Decomposer(store, Chunker.Params.of(16384, 1 << 20))
                    .decompose(ByteSource.ofFile(fake), wd);
            assertTrue(bp.root() instanceof Node.Blob, "unparseable input must degrade to blocks");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WorkDir rwd = WorkDir.createTemp(tmp.resolve("work"), "r-")) {
                new Reassembler(store, rwd).writeVerified(bp, out);
            }
            assertArrayEqualsFile(fake, out.toByteArray());
        }
    }

    @Test
    void blockStoreSurvivesReopening(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("blocks");
        Hash h;
        byte[] payload = "persisted block".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ChunkStore store = ChunkStore.open(dir)) {
            h = Hashes.of(payload);
            assertTrue(store.put(h, payload));
            assertFalse(store.put(h, payload), "storing the same block twice must be a no-op");
        }
        try (ChunkStore store = ChunkStore.open(dir)) {
            assertTrue(store.contains(h));
            assertArrayEquals(payload, store.get(h));
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    private static void assertArrayEqualsFile(Path expected, byte[] actual) throws IOException {
        byte[] want = Files.readAllBytes(expected);
        assertEquals(want.length, actual.length, "rebuilt size differs");
        assertNotNull(actual);
        org.junit.jupiter.api.Assertions.assertArrayEquals(want, actual, "rebuilt bytes differ");
    }
}
