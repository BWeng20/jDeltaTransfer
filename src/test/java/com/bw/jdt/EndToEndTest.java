package com.bw.jdt;

import com.bw.jdt.client.DeltaClient;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.server.HttpApi;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server and client over a real HTTP socket, including the delta path. */
class EndToEndTest {

    private static final int MAX_BLOCK = 1 << 20;

    @Test
    void deltaTransferRebuildsTheNewVersionFromTheOldOne(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "3",
                "--start-size", "16MB", "--growth", "0.1", "--threads", "2"});

        Chunker.Params params = Chunker.Params.of(32768, MAX_BLOCK);
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives, params, true, false);
        int ingested = store.scan(VersionStore.Log.STDOUT);
        assertEquals(3, ingested);

        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();
        try (DeltaClient client = new DeltaClient(URI.create("http://127.0.0.1:" + api.port()),
                tmp.resolve("cache"), DeltaClient.Log.STDOUT)) {

            var list = client.listVersions();
            assertEquals(3, list.path("count").asInt());
            for (var v : list.path("versions")) {
                Path file = archives.resolve(v.path("fileName").asText());
                assertEquals(Hashes.ofFile(file).hex(), v.path("sha256").asText(),
                        "server published a wrong hash for " + file.getFileName());
            }

            // Full download of v1.
            Path full = tmp.resolve("out-v01.zip");
            var fullResult = client.fetchFull("archive-v01", full);
            assertEquals(Hashes.ofFile(archives.resolve("archive-v01.zip")), fullResult.archiveHash());
            assertArrayEqualsFiles(archives.resolve("archive-v01.zip"), full);

            // Delta from v1 to v2.
            Path delta = tmp.resolve("out-v02.zip");
            var deltaResult = client.fetchDelta("archive-v02", full, delta);
            assertArrayEqualsFiles(archives.resolve("archive-v02.zip"), delta);
            assertTrue(deltaResult.transferredBytes() < deltaResult.archiveSize() / 2,
                    "delta should be far smaller than the archive, was "
                            + deltaResult.transferredBytes() + " of " + deltaResult.archiveSize());

            // And on to v3, using the just rebuilt v2 as base.
            Path delta3 = tmp.resolve("out-v03.zip");
            var r3 = client.fetchDelta("archive-v03", delta, delta3);
            assertArrayEqualsFiles(archives.resolve("archive-v03.zip"), delta3);
            assertTrue(r3.blocksReused() > 0);

            System.out.printf("v1->v2 transferred %d of %d bytes (%.1f%% saved)%n",
                    deltaResult.transferredBytes(), deltaResult.archiveSize(),
                    deltaResult.savedFraction() * 100);
        } finally {
            api.close();
            store.close();
        }
    }

    /**
     * A store that no longer keeps the original archive file must still be able to serve the
     * full version, rebuilt from its blueprint and blocks.
     */
    @Test
    void fullDownloadWorksWithoutTheOriginalArchiveFile(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "12MB", "--threads", "1"});
        Path original = archives.resolve("archive-v01.zip");
        var expected = Hashes.ofFile(original);

        Chunker.Params params = Chunker.Params.of(32768, MAX_BLOCK);
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives, params, true, false);
        store.scan(VersionStore.Log.STDOUT);
        Files.delete(original);

        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();
        try (DeltaClient client = new DeltaClient(URI.create("http://127.0.0.1:" + api.port()),
                tmp.resolve("cache"), DeltaClient.Log.STDOUT)) {
            Path out = tmp.resolve("rebuilt.zip");
            var result = client.fetchFull("archive-v01", out);
            assertEquals(expected, result.archiveHash());
            assertEquals(expected, Hashes.ofFile(out));
        } finally {
            api.close();
            store.close();
        }
    }

    @Test
    void hashesSurviveAServerRestart(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "8MB", "--threads", "1"});

        Chunker.Params params = Chunker.Params.of(32768, MAX_BLOCK);
        String sha;
        try (VersionStore store = VersionStore.open(tmp.resolve("store"), archives, params, true, false)) {
            store.scan(VersionStore.Log.STDOUT);
            sha = store.version("archive-v01").sha256();
        }
        assertTrue(Files.exists(tmp.resolve("store").resolve("index.json")),
                "hashes must be persisted on disk");

        try (VersionStore reopened = VersionStore.open(tmp.resolve("store"), archives, params, true, false)) {
            // A second scan must not re-ingest anything, and the hash must still be there.
            assertEquals(0, reopened.scan(VersionStore.Log.STDOUT));
            assertEquals(sha, reopened.version("archive-v01").sha256());
        }
    }

    @Test
    void startingWithDifferentBlockLimitsIsRefused(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        Files.createDirectories(archives);
        try (VersionStore store = VersionStore.open(tmp.resolve("store"),
                archives, Chunker.Params.of(32768, MAX_BLOCK), true, false)) {
            store.scan(VersionStore.Log.STDOUT);
        }
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () ->
                VersionStore.open(tmp.resolve("store"), archives,
                        Chunker.Params.of(8192, MAX_BLOCK), true, false));
    }

    private static void assertArrayEqualsFiles(Path expected, Path actual) throws Exception {
        assertEquals(Files.size(expected), Files.size(actual), "size differs");
        assertEquals(Hashes.ofFile(expected), Hashes.ofFile(actual), "content differs");
    }
}
