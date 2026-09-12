package com.bw.jdt.core;

import com.bw.jdt.core.format.ContainerCodec;
import com.bw.jdt.core.format.SevenZCodec;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 7z is the one format whose exact re-encoding cannot be guaranteed by construction, so this
 * pins down that the codec really does reproduce the nested cabinets the generator writes.
 */
class SevenZCodecProbeTest {

    @Test
    void nestedSevenZDecomposesAndRebuildsByteForByte(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "12MB", "--threads", "1"});

        Path nested = extractFirst(archives.resolve("archive-v01.zip"), ".7z", tmp.resolve("nested.7z"));
        assertNotNull(nested, "the generator must place at least one nested 7z in every version");

        try (WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "p-")) {
            ContainerCodec.Decomposition d = new SevenZCodec().decompose(ByteSource.ofFile(nested), wd);
            assertNotNull(d, "the nested 7z should be decomposable, not opaque");
            assertTrue(d.parts().size() >= 1);

            Path rebuilt = tmp.resolve("rebuilt.7z");
            try (OutputStream out = Files.newOutputStream(rebuilt)) {
                new SevenZCodec().rebuild(d.meta(), d.parts(), out);
            }
            assertEquals(Hashes.ofFile(nested), Hashes.ofFile(rebuilt));
        }
    }

    private static Path extractFirst(Path zip, String suffix, Path target) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().endsWith(suffix)) {
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zin.transferTo(out);
                    }
                    return target;
                }
            }
        }
        return null;
    }
}
