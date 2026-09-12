package com.bw.jdt.core;

import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The two concerns that are specific to 7z sitting on top, rather than any format sitting on top
 * (which {@link TopLevelFormatTest} covers): the size limit applies to the outermost container
 * too, and parallelism buys nothing because 7z packs its entries into one solid LZMA2 stream --
 * unlike ZIP entries they are not independently compressed, so they cannot be re-encoded
 * independently either.
 */
class TopLevelSevenZTest {

    private static Path generate(Path dir, int count, String size) throws Exception {
        GenerateTestArchives.main(new String[]{
                "--out", dir.toString(), "--count", String.valueOf(count),
                "--start-size", size, "--growth", "0.1", "--threads", "2",
                "--outer-format", "7z"});
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

    /**
     * How much --rebuild-threads actually buys with 7z on top. Off by default because it
     * re-encodes hundreds of megabytes with LZMA2: {@code gradlew test -Pbench --tests '*TopLevelSevenZTest*'}
     */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "jdt.bench", matches = "true")
    @Test
    void measureParallelRebuildWithSevenZOnTop(@TempDir Path tmp) throws Exception {
        Path archives = generate(tmp.resolve("archives"), 1, "128MB");
        Path archive = archives.resolve("archive-v01.7z");

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"))) {
            long t0 = System.nanoTime();
            Blueprint bp = decompose(archive, store, tmp.resolve("work"), Decomposer.Listener.NOOP);
            long decomposeNs = System.nanoTime() - t0;

            long nested = bp.root() instanceof Node.Container c
                    ? c.children().stream().filter(n -> n instanceof Node.Container).count() : 0;

            System.out.printf(Locale.ROOT, "%narchive %s, %d nested containers, decompose %s%n",
                    Fmt.human(Files.size(archive)), nested, Fmt.seconds(decomposeNs));
            System.out.printf(Locale.ROOT, "%-18s %10s %10s%n", "rebuild-threads", "time", "speedup");

            long serialNs = 0;
            for (int threads : new int[]{1, 8, 16}) {
                long t = System.nanoTime();
                byte[] rebuilt = rebuild(bp, store, tmp.resolve("work"), threads);
                long ns = System.nanoTime() - t;
                if (threads == 1) {
                    serialNs = ns;
                }
                assertEquals(Hashes.ofFile(archive), Hashes.of(rebuilt));
                System.out.printf(Locale.ROOT, "%-18d %10s %10.2f%n",
                        threads, Fmt.seconds(ns), serialNs / (double) ns);
            }

            // The alternative to re-encoding at all: same content, cheaper shape.
            for (Repacker.Mode mode : Repacker.Mode.values()) {
                Path target = mode == Repacker.Mode.ZIP
                        ? tmp.resolve("repacked.zip")
                        : tmp.resolve("tree");
                long t = System.nanoTime();
                try (WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "pack-")) {
                    Repacker packer = new Repacker(store, wd).withThreads(8);
                    Repacker.Result r = packer.write(bp, mode, target);
                    packer.verifyEntries(bp, mode, target);
                    long ns = System.nanoTime() - t;
                    System.out.printf(Locale.ROOT, "%-18s %10s %10.2f  (%d members, output %s)%n",
                            "repack " + mode, Fmt.seconds(ns), serialNs / (double) ns,
                            r.entries(), Fmt.human(r.outputBytes()));
                }
            }
        }
    }

    /**
     * The limit that decides whether a 7z is opened applies to the outermost container too. Above
     * it, a top level 7z becomes one opaque blob and the delta collapses -- worth knowing before
     * pointing the server at multi gigabyte 7z archives.
     */
    @Test
    void aTopLevelSevenZOverTheLimitBecomesOpaque(@TempDir Path tmp) throws Exception {
        Path archives = generate(tmp.resolve("archives"), 1, "12MB");
        Path archive = archives.resolve("archive-v01.7z");
        long size = Files.size(archive);

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"));
             WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "d-")) {
            Blueprint bp = new Decomposer(store, Chunker.Params.of(65536, 4 << 20),
                    new DecomposeLimits(size - 1)).decompose(ByteSource.ofFile(archive), wd);
            assertInstanceOf(Node.Blob.class, bp.root(),
                    "over the limit the whole archive has to degrade to plain blocks");

            // Still rebuildable, just without any delta benefit.
            byte[] rebuilt = rebuild(bp, store, tmp.resolve("work"), 8);
            assertEquals(Hashes.ofFile(archive), Hashes.of(rebuilt));
        }
    }
}
