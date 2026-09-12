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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The intended deployment: one short lived client process per upgrade, restarted later for the
 * next version. Each run must find the index its predecessor left behind instead of decomposing
 * the base again, which is otherwise close to half of a transfer's wall clock.
 */
class CacheHandoverTest {

    private static final int MAX_BLOCK = 1 << 20;

    @Test
    void aLaterRunReusesTheIndexTheEarlierRunLeftBehind(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "3",
                "--start-size", "16MB", "--growth", "0.1", "--threads", "2"});

        Chunker.Params params = Chunker.Params.of(32768, MAX_BLOCK);
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives, params, true, false);
        store.scan(VersionStore.Log.STDOUT);
        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();

        URI server = URI.create("http://127.0.0.1:" + api.port());
        Path cache = tmp.resolve("cache");
        Path v1 = tmp.resolve("local-v01.zip");
        Path v2 = tmp.resolve("local-v02.zip");
        Path v3 = tmp.resolve("local-v03.zip");

        try {
            // Run 1: get the first version, then upgrade. Indexing is unavoidable here.
            try (DeltaClient c = new DeltaClient(server, cache, DeltaClient.Log.STDOUT)) {
                c.fetchFull("archive-v01", v1);
                var r = c.fetchDelta("archive-v02", v1, v2);
                assertTrue(r.phases().indexNanos() > 0, "the first upgrade has to index its base");
            }

            // Run 2: a fresh client, upgrading from the file run 1 produced.
            try (DeltaClient c = new DeltaClient(server, cache, DeltaClient.Log.STDOUT)) {
                var r = c.fetchDelta("archive-v03", v2, v3);
                assertEquals(0, r.phases().indexNanos(),
                        "the handed over cache must make indexing unnecessary");
                assertTrue(r.blocksReused() > 0);
            }

            assertEquals(Hashes.ofFile(archives.resolve("archive-v03.zip")), Hashes.ofFile(v3));
        } finally {
            api.close();
            store.close();
        }
    }

    @Test
    void handoverConsumesTheBaseIndexUnlessAskedToKeepIt(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "2",
                "--start-size", "12MB", "--growth", "0.1", "--threads", "2"});

        Chunker.Params params = Chunker.Params.of(32768, MAX_BLOCK);
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives, params, true, false);
        store.scan(VersionStore.Log.STDOUT);
        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();
        URI server = URI.create("http://127.0.0.1:" + api.port());

        try {
            Path cacheA = tmp.resolve("cache-a");
            Path a1 = tmp.resolve("a-v01.zip");
            Path a2 = tmp.resolve("a-v02.zip");
            try (DeltaClient c = new DeltaClient(server, cacheA, DeltaClient.Log.STDOUT)) {
                c.fetchFull("archive-v01", a1);
                c.fetchDelta("archive-v02", a1, a2);
            }
            assertEquals(1, countCaches(cacheA),
                    "renaming leaves exactly one index behind, the one for the new version");

            Path cacheB = tmp.resolve("cache-b");
            Path b1 = tmp.resolve("b-v01.zip");
            Path b2 = tmp.resolve("b-v02.zip");
            try (DeltaClient c = new DeltaClient(server, cacheB, DeltaClient.Log.STDOUT)
                    .keepBaseCache(true)) {
                c.fetchFull("archive-v01", b1);
                c.fetchDelta("archive-v02", b1, b2);
            }
            assertEquals(2, countCaches(cacheB), "--keep-base-cache keeps both indexes");
        } finally {
            api.close();
            store.close();
        }
    }

    /** Counts cache directories, ignoring the transient incoming staging areas. */
    private static long countCaches(Path cacheDir) throws Exception {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        try (var s = Files.list(cacheDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().contains("-incoming-"))
                    .count();
        }
    }

    @Test
    void aStaleCacheDirectoryIsNotMistakenForAnIndex(@TempDir Path tmp) throws Exception {
        // An empty directory left over from an interrupted run must trigger re-indexing,
        // not be accepted as an empty index.
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache.resolve("deadbeefdeadbeefdeadbeef-65536-262144-7z536870912"));
        assertFalse(Files.exists(cache.resolve("deadbeefdeadbeefdeadbeef-65536-262144-7z536870912")
                .resolve("index.bin")));
    }
}
