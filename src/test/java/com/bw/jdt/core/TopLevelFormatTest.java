package com.bw.jdt.core;

import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outermost container may be any supported format, not just ZIP. Nothing in decomposition,
 * reassembly or the block store treats the top level specially: the format is decided by the
 * magic bytes exactly like at every nested level.
 */
class TopLevelFormatTest {

    private static Path generate(Path dir, int count, String size, String outerFormat)
            throws Exception {
        GenerateTestArchives.main(new String[]{
                "--out", dir.toString(), "--count", String.valueOf(count),
                "--start-size", size, "--growth", "0.1", "--threads", "2",
                "--outer-format", outerFormat});
        return dir;
    }

    private static Blueprint decompose(Path archive, ChunkStore store, Path work,
                                       Decomposer.Listener listener) throws Exception {
        try (WorkDir wd = WorkDir.createTemp(work, "d-")) {
            return new Decomposer(store, Chunker.Params.of(65536, 4 << 20),
                    DecomposeLimits.DEFAULT, Decomposer.DEFAULT_MAX_DEPTH, listener)
                    .decompose(ByteSource.ofFile(archive), wd);
        }
    }

    private static byte[] rebuild(Blueprint bp, ChunkStore store, Path work, int threads)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WorkDir wd = WorkDir.createTemp(work, "r" + threads + "-")) {
            new Reassembler(store, wd).withThreads(threads).writeVerified(bp, out);
        }
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0} on top")
    @CsvSource({"zip, ZIP", "cab, CAB", "7z, SEVEN_Z"})
    void anySupportedFormatCanBeTheOutermostContainer(String outerFormat, ContainerFormat expected,
                                                      @TempDir Path tmp) throws Exception {
        Path archives = generate(tmp.resolve("archives"), 1, "16MB", outerFormat);
        Path archive = archives.resolve("archive-v01." + outerFormat);
        assertTrue(Files.exists(archive), "the generator must produce " + archive.getFileName());

        List<ContainerFormat> seen = new ArrayList<>();
        List<ContainerFormat> opaque = new ArrayList<>();
        Decomposer.Listener listener = new Decomposer.Listener() {
            @Override
            public synchronized void onContainer(ContainerFormat format, int depth, long size) {
                seen.add(format);
            }

            @Override
            public synchronized void onOpaque(ContainerFormat attempted, int depth, long size) {
                opaque.add(attempted);
            }
        };

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"))) {
            Blueprint bp = decompose(archive, store, tmp.resolve("work"), listener);

            assertInstanceOf(Node.Container.class, bp.root(),
                    outerFormat + " on top must be opened up, not left opaque");
            assertEquals(expected, ((Node.Container) bp.root()).format());
            assertTrue(opaque.isEmpty(), "nothing should fall back to opaque: " + opaque);
            // Whatever sits on top, the nested archives inside are still opened.
            assertTrue(seen.contains(ContainerFormat.ZIP), "nested ZIPs: " + seen);
            assertTrue(seen.contains(ContainerFormat.CAB), "nested CABs: " + seen);

            byte[] serial = rebuild(bp, store, tmp.resolve("work"), 1);
            byte[] parallel = rebuild(bp, store, tmp.resolve("work"), 8);
            assertArrayEquals(serial, parallel, "parallel rebuild changed the bytes");
            assertArrayEquals(Files.readAllBytes(archive), parallel);
        }
    }

    @ParameterizedTest(name = "{0} on top")
    @CsvSource({"zip", "cab", "7z"})
    void consecutiveVersionsShareBlocksWhateverSitsOnTop(String outerFormat, @TempDir Path tmp)
            throws Exception {
        Path archives = generate(tmp.resolve("archives"), 2, "16MB", outerFormat);

        try (ChunkStore a = ChunkStore.open(tmp.resolve("a"));
             ChunkStore b = ChunkStore.open(tmp.resolve("b"))) {
            decompose(archives.resolve("archive-v01." + outerFormat), a,
                    tmp.resolve("work"), Decomposer.Listener.NOOP);
            Blueprint bpB = decompose(archives.resolve("archive-v02." + outerFormat), b,
                    tmp.resolve("work"), Decomposer.Listener.NOOP);

            var needed = bpB.distinctChunks();
            long reusable = needed.entrySet().stream()
                    .filter(e -> a.contains(e.getKey()))
                    .mapToLong(java.util.Map.Entry::getValue).sum();
            long total = needed.values().stream().mapToLong(Integer::longValue).sum();
            double share = (double) reusable / total;

            System.out.printf(Locale.ROOT, "%-4s on top: block reuse %.1f%%%n",
                    outerFormat, share * 100);
            assertTrue(share > 0.5, outerFormat + " on top: reuse was only "
                    + Math.round(share * 100) + "%");
        }
    }
}
