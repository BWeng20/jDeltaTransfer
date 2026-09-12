package com.bw.jdt.core;

import com.bw.jdt.core.format.ContainerCodec;
import com.bw.jdt.core.format.SevenZCodec;
import com.bw.jdt.tools.Content;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;

/**
 * Measures what raising {@code SevenZCodec}'s size limit would actually cost.
 *
 * <p>Off by default because it compresses hundreds of megabytes with LZMA2. Run it with:
 * {@code gradlew test --tests '*SevenZCostBenchmark*' -Pbench}
 */
@EnabledIfSystemProperty(named = "jdt.bench", matches = "true")
class SevenZCostBenchmark {

    private static final int[] SIZES_MB = {16, 48, 96};

    @Test
    void measureDecomposeAndRebuildCost(@TempDir Path tmp) throws Exception {
        System.out.printf(Locale.ROOT, "%n%-10s %12s %12s %14s %14s %12s%n",
                "raw", "7z size", "write", "decompose", "rebuild", "rebuild MB/s");
        for (int mb : SIZES_MB) {
            long rawBytes = mb * 1024L * 1024L;
            Path sevenZ = tmp.resolve("bench-" + mb + ".7z");

            long t0 = System.nanoTime();
            writeSevenZ(sevenZ, rawBytes);
            long writeNs = System.nanoTime() - t0;
            long packed = Files.size(sevenZ);

            // Decomposition includes the round trip verification: the codec rebuilds the whole
            // container and compares its hash, which is where the LZMA2 cost lands.
            long t1 = System.nanoTime();
            ContainerCodec.Decomposition d;
            try (WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "bench-")) {
                d = new SevenZCodec().decompose(ByteSource.ofFile(sevenZ), wd, DecomposeLimits.DEFAULT);
                long decomposeNs = System.nanoTime() - t1;

                if (d == null) {
                    System.out.printf(Locale.ROOT, "%-10s %12s %12s %14s%n",
                            mb + " MiB", bytes(packed), secs(writeNs), "OPAQUE (over the limit)");
                    continue;
                }

                // Rebuild alone: this is what a client pays on every single delta transfer.
                long t2 = System.nanoTime();
                try (OutputStream out = OutputStream.nullOutputStream()) {
                    new SevenZCodec().rebuild(d.meta(), d.parts(), out, 1);
                }
                long rebuildNs = System.nanoTime() - t2;

                System.out.printf(Locale.ROOT, "%-10s %12s %12s %14s %14s %12.1f%n",
                        mb + " MiB", bytes(packed), secs(writeNs), secs(decomposeNs), secs(rebuildNs),
                        rawBytes / 1048576.0 / (rebuildNs / 1e9));
            }
        }
        System.out.println("\n(decompose = read + spill + round trip verify; rebuild = one LZMA2 pass)");
    }

    private static void writeSevenZ(Path target, long rawBytes) throws Exception {
        Files.deleteIfExists(target);
        try (SevenZOutputFile sz = new SevenZOutputFile(target.toFile())) {
            sz.setContentMethods(Collections.singletonList(new SevenZMethodConfiguration(SevenZMethod.LZMA2)));
            // A handful of entries with the same mixed content the generator produces.
            int entries = 8;
            long per = rawBytes / entries;
            for (int i = 0; i < entries; i++) {
                long id = 9000 + i;
                Content.Kind kind = i % 3 == 0 ? Content.Kind.TEXT : Content.Kind.BINARY;
                SevenZArchiveEntry e = new SevenZArchiveEntry();
                e.setName("bench" + id + ".dat");
                e.setDirectory(false);
                e.setSize(per);
                e.setHasStream(true);
                e.setLastModifiedDate(new java.util.Date(1_700_000_000_000L + id));
                sz.putArchiveEntry(e);
                try (InputStream in = Content.open(id, 0, per, kind)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        sz.write(buf, 0, n);
                    }
                }
                sz.closeArchiveEntry();
            }
            sz.finish();
        }
    }

    private static String bytes(long b) {
        return Fmt.human(b);
    }

    private static String secs(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1e9);
    }
}
