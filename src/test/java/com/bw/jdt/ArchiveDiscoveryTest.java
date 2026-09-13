package com.bw.jdt;

import com.bw.jdt.core.Chunker;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server accepts as a version. The format is decided by the magic bytes, not by the
 * file name, so any supported container is ingested whatever it is called.
 */
class ArchiveDiscoveryTest {

    private static final int MAX_BLOCK = 1 << 20;

    /** Captures warnings so the test can assert on what the operator is told. */
    private static final class RecordingLog implements VersionStore.Log {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
            System.out.println("[store] " + message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
            System.out.println("[store] WARN " + message);
        }
    }

    private static VersionStore open(Path tmp, Path archives) throws Exception {
        return VersionStore.open(tmp.resolve("store"), archives,
                Chunker.Params.of(32768, MAX_BLOCK), true, false);
    }

    @Test
    void anArchiveWithAnUnrelatedExtensionIsStillIngested(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "8MB", "--threads", "1"});

        // Real world archives are called all sorts of things: .pak, .msi, .jar, no extension.
        Files.move(archives.resolve("archive-v01.zip"), archives.resolve("bundle.pak"));

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(1, store.scan(log));
            assertNotNull(store.version("bundle"), "a ZIP named .pak must be found by its magic bytes");
        }
    }

    @Test
    void cabAndSevenZOnTopAreDiscoveredToo(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        for (String fmt : new String[]{"zip", "cab", "7z"}) {
            Path staging = tmp.resolve("staging-" + fmt);
            GenerateTestArchives.main(new String[]{
                    "--out", staging.toString(), "--count", "1", "--start-size", "8MB",
                    "--threads", "1", "--outer-format", fmt});
            Files.createDirectories(archives);
            Files.move(staging.resolve("archive-v01." + fmt), archives.resolve("outer-" + fmt + "." + fmt));
        }

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(3, store.scan(log));
            for (String fmt : new String[]{"zip", "cab", "7z"}) {
                assertNotNull(store.version("outer-" + fmt), fmt + " on top was not discovered");
                assertEquals(0, store.version("outer-" + fmt).opaqueContainers(),
                        fmt + " on top should be decomposed, not opaque");
            }
        }
    }

    @Test
    void filesThatAreNotArchivesAreIgnored(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        Files.createDirectories(archives);
        Files.writeString(archives.resolve("readme.txt"), "not an archive");
        Files.write(archives.resolve("noise.bin"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(0, store.scan(log));
            assertTrue(store.versions().isEmpty());
        }
    }

    /** A half copied archive must not be ingested as a truncated version. */
    @Test
    void inProgressFilesAreSkipped(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "8MB", "--threads", "1"});
        Files.move(archives.resolve("archive-v01.zip"), archives.resolve("incoming.zip.part"));

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(0, store.scan(log));
        }
    }

    /**
     * Two formats of the same name collapse onto one version id. The second must be reported and
     * skipped rather than quietly replacing the first in the index.
     */
    @Test
    void collidingVersionIdsAreReportedNotSilentlyMerged(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        Files.createDirectories(archives);
        for (String fmt : new String[]{"zip", "7z"}) {
            Path staging = tmp.resolve("staging-" + fmt);
            GenerateTestArchives.main(new String[]{
                    "--out", staging.toString(), "--count", "1", "--start-size", "8MB",
                    "--threads", "1", "--outer-format", fmt});
            Files.move(staging.resolve("archive-v01." + fmt), archives.resolve("app." + fmt));
        }

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(1, store.scan(log), "only one of the two colliding files may be ingested");
            assertNotNull(store.version("app"));
            assertNull(store.version("app.zip"));
            assertTrue(log.warnings.stream().anyMatch(w -> w.contains("already taken")),
                    "the operator must be told about the collision, got " + log.warnings);
        }
    }

    /**
     * An archive replaced by one of the same size must not keep its old blueprint. The size
     * cannot tell them apart, so the scan compares the file date as well.
     */
    @Test
    void aNewerFileDateIsIngestedAgainEvenAtTheSameSize(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "8MB", "--threads", "1"});
        Path archive = archives.resolve("archive-v01.zip");

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(1, store.scan(log));
            assertEquals(0, store.scan(log), "an unchanged file must not be ingested again");

            Files.setLastModifiedTime(archive,
                    FileTime.from(Files.getLastModifiedTime(archive).toInstant().plusSeconds(3600)));
            assertEquals(1, store.scan(log), "same size, newer date: must be ingested again");
            assertEquals(Files.getLastModifiedTime(archive).toInstant().toString(),
                    store.version("archive-v01").fileModified());
        }
    }

    /** A store indexed before file dates were recorded must not be decomposed all over again. */
    @Test
    void anIndexWithoutFileDatesIsAdoptedWithoutReingesting(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "8MB", "--threads", "1"});
        Path indexFile = tmp.resolve("store").resolve("index.json");

        RecordingLog log = new RecordingLog();
        try (VersionStore store = open(tmp, archives)) {
            assertEquals(1, store.scan(log));
        }
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = (ObjectNode) json.readTree(indexFile.toFile());
        root.withArray("versions").forEach(v -> ((ObjectNode) v).remove("fileModified"));
        json.writeValue(indexFile.toFile(), root);

        try (VersionStore store = open(tmp, archives)) {
            assertEquals(0, store.scan(log), "an old index entry must be trusted, not re-ingested");
            assertNotNull(store.version("archive-v01").fileModified());
        }
        assertTrue(json.readTree(indexFile.toFile()).path("versions").get(0).hasNonNull("fileModified"),
                "the adopted date must be written to index.json");
    }
}
