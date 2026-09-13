package com.bw.jdt;

import com.bw.jdt.client.DeltaClient;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.server.HttpApi;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --rebuild-as zip|extract} gives up the archive's byte identity on purpose, to escape the
 * sequential LZMA2 pass a solid 7z demands. What must survive is the content: every member has to
 * come out with exactly the bytes the original held.
 */
class RepackTest {

    private static final int MAX_BLOCK = 1 << 20;

    /**
     * Reads an archive's members into name -> content hash, so the same content can be compared
     * across two different container shapes. Dispatches on the magic bytes, since the reference
     * side may be a 7z while the repacked side is always a ZIP.
     */
    private static Map<String, String> membersOf(Path archive) throws Exception {
        Map<String, String> out = new HashMap<>();
        if (isZip(archive)) {
            try (ZipFile zf = new ZipFile(archive.toFile())) {
                var it = zf.entries();
                while (it.hasMoreElements()) {
                    ZipEntry e = it.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    try (var in = zf.getInputStream(e)) {
                        out.put(e.getName(), Hashes.of(in).hex());
                    }
                }
            }
            return out;
        }
        try (var sz = org.apache.commons.compress.archivers.sevenz.SevenZFile.builder()
                .setPath(archive).get()) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                var md = Hashes.newDigest();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = sz.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
                out.put(e.getName(), com.bw.jdt.core.Hash.wrap(md.digest()).hex());
            }
        }
        return out;
    }

    private record Fixture(VersionStore store, HttpApi api, URI server, Path archives) {
    }

    private static Fixture serve(Path tmp, String outerFormat, int count) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", String.valueOf(count),
                "--start-size", "16MB", "--growth", "0.1", "--threads", "2",
                "--outer-format", outerFormat});
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives,
                Chunker.Params.of(32768, MAX_BLOCK), true, false);
        store.scan(VersionStore.Log.STDOUT);
        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();
        return new Fixture(store, api, URI.create("http://127.0.0.1:" + api.port()), archives);
    }

    @ParameterizedTest(name = "{0} on top")
    @CsvSource({"zip", "7z"})
    void repackingAsZipKeepsEveryMembersContent(String outerFormat, @TempDir Path tmp)
            throws Exception {
        Fixture f = serve(tmp, outerFormat, 2);
        try (DeltaClient plain = new DeltaClient(f.server(), tmp.resolve("c1"), DeltaClient.Log.STDOUT);
             DeltaClient repacking = new DeltaClient(f.server(), tmp.resolve("c2"), DeltaClient.Log.STDOUT)
                     .rebuildAs(DeltaClient.RebuildAs.ZIP)) {

            Path base = tmp.resolve("base." + outerFormat);
            plain.fetchFull("archive-v01", base);

            // The reference: a bit exact rebuild of v02.
            Path exact = tmp.resolve("exact." + outerFormat);
            plain.fetchDelta("archive-v02", base, exact);
            assertEquals(Hashes.ofFile(f.archives().resolve("archive-v02." + outerFormat)),
                    Hashes.ofFile(exact));

            // The same transfer, repacked.
            Path repacked = tmp.resolve("repacked.zip");
            repacking.fetchDelta("archive-v02", base, repacked);

            assertTrue(Files.exists(repacked));
            assertNotEquals(Hashes.ofFile(exact), Hashes.ofFile(repacked),
                    "a repack is deliberately not the same file");
            assertEquals(membersOf(exact), membersOf(repacked),
                    "but every member must carry identical content");
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    @ParameterizedTest(name = "{0} on top")
    @ValueSource(strings = {"zip", "7z"})
    void extractingWritesTheMembersAsFiles(String outerFormat, @TempDir Path tmp) throws Exception {
        Fixture f = serve(tmp, outerFormat, 1);
        try (DeltaClient plain = new DeltaClient(f.server(), tmp.resolve("c1"), DeltaClient.Log.STDOUT);
             DeltaClient extracting = new DeltaClient(f.server(), tmp.resolve("c2"), DeltaClient.Log.STDOUT)
                     .rebuildAs(DeltaClient.RebuildAs.EXTRACT)) {

            Path original = tmp.resolve("original." + outerFormat);
            plain.fetchFull("archive-v01", original);

            Path dir = tmp.resolve("tree");
            // Extraction needs a delta transfer; a full download is a plain byte stream.
            extracting.fetchDelta("archive-v01", original, dir);

            assertTrue(Files.isDirectory(dir), "extract mode must produce a directory");
            assertTrue(Files.exists(dir.resolve("manifest.txt")));
            try (var walk = Files.walk(dir)) {
                assertTrue(walk.filter(Files::isRegularFile).count() > 5);
            }
            // Nested archives stay single files rather than being unpacked further.
            try (var walk = Files.walk(dir.resolve("nested"))) {
                assertTrue(walk.filter(Files::isRegularFile)
                        .anyMatch(p -> p.getFileName().toString().endsWith(".cab")));
            }
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /**
     * A repack cannot serve as the base of the next delta, so the cache must stay under the base's
     * own key instead of being relabelled to a file that was never written.
     */
    @ParameterizedTest(name = "{0} on top")
    @ValueSource(strings = {"7z"})
    void repackingDoesNotHandTheCacheOver(String outerFormat, @TempDir Path tmp) throws Exception {
        Fixture f = serve(tmp, outerFormat, 2);
        Path cache = tmp.resolve("cache");
        try (DeltaClient plain = new DeltaClient(f.server(), cache, DeltaClient.Log.STDOUT);
             DeltaClient repacking = new DeltaClient(f.server(), cache, DeltaClient.Log.STDOUT)
                     .rebuildAs(DeltaClient.RebuildAs.ZIP)) {

            Path base = tmp.resolve("base." + outerFormat);
            plain.fetchFull("archive-v01", base);
            repacking.fetchDelta("archive-v02", base, tmp.resolve("out.zip"));

            // The base's index is still there, so a second run from the same base reuses it.
            var second = repacking.fetchDelta("archive-v02", base, tmp.resolve("out2.zip"));
            assertEquals(0, second.phases().indexNanos(),
                    "the base cache must survive a repack and be reused");
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /**
     * A cabinet on top can be listed, but there is no CAB reader here to read the reference
     * archive with. Instead the two independent write paths are compared against each other:
     * both derive their members from the blueprint, and the client has already checked each of
     * them against it, so agreeing on every member is a real check that the mapping is right.
     */
    @org.junit.jupiter.api.Test
    void repackingACabinetYieldsTheSameMembersEitherWay(@TempDir Path tmp) throws Exception {
        Fixture f = serve(tmp, "cab", 2);
        try (DeltaClient plain = new DeltaClient(f.server(), tmp.resolve("c1"), DeltaClient.Log.STDOUT);
             DeltaClient asZip = new DeltaClient(f.server(), tmp.resolve("c2"), DeltaClient.Log.STDOUT)
                     .rebuildAs(DeltaClient.RebuildAs.ZIP);
             DeltaClient asTree = new DeltaClient(f.server(), tmp.resolve("c3"), DeltaClient.Log.STDOUT)
                     .rebuildAs(DeltaClient.RebuildAs.EXTRACT)) {

            Path base = tmp.resolve("base.cab");
            plain.fetchFull("archive-v01", base);

            Path zip = tmp.resolve("out.zip");
            asZip.fetchDelta("archive-v02", base, zip);
            assertTrue(isZip(zip), "a listable cabinet must be repacked, not fall back");

            Path tree = tmp.resolve("tree");
            asTree.fetchDelta("archive-v02", base, tree);

            Map<String, String> fromZip = membersOf(zip);
            Map<String, String> fromTree = new HashMap<>();
            try (var walk = Files.walk(tree)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    fromTree.put(tree.relativize(p).toString().replace('\\', '/'),
                            Hashes.ofFile(p).hex());
                }
            }
            assertEquals(fromZip, fromTree, "both repack shapes must carry the same members");
            assertTrue(fromZip.containsKey("manifest.txt"), "members were: " + fromZip.keySet());
            assertTrue(fromZip.keySet().stream().anyMatch(n -> n.startsWith("nested/")));
            assertTrue(fromZip.size() > 50, "expected many members, got " + fromZip.size());
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /**
     * An outer container that was left opaque has no members at all, so a repack has nothing to
     * work from and must degrade to the original format instead of failing. Forced here by giving
     * the server a 7z limit below the archive's own size.
     */
    @org.junit.jupiter.api.Test
    void anOpaqueOuterContainerFallsBackToTheOriginalFormat(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "2", "--start-size", "12MB",
                "--growth", "0.1", "--threads", "2", "--outer-format", "7z"});

        VersionStore store = VersionStore.open(tmp.resolve("store"), archives,
                Chunker.Params.of(32768, MAX_BLOCK), true, false);
        store.setLimits(new com.bw.jdt.core.DecomposeLimits(1024));
        store.scan(VersionStore.Log.STDOUT);
        assertTrue(store.version("archive-v01").opaqueContainers() > 0,
                "the 7z limit should have forced the outer container to stay opaque");

        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();
        URI server = URI.create("http://127.0.0.1:" + api.port());
        try (DeltaClient plain = new DeltaClient(server, tmp.resolve("c1"), DeltaClient.Log.STDOUT);
             DeltaClient repacking = new DeltaClient(server, tmp.resolve("c2"), DeltaClient.Log.STDOUT)
                     .rebuildAs(DeltaClient.RebuildAs.ZIP)) {

            Path base = tmp.resolve("base.7z");
            plain.fetchFull("archive-v01", base);

            Path out = tmp.resolve("out.bin");
            repacking.fetchDelta("archive-v02", base, out);

            assertEquals(Hashes.ofFile(archives.resolve("archive-v02.7z")), Hashes.ofFile(out));
            assertFalse(isZip(out), "the fallback must be the original 7z, not a ZIP");
        } finally {
            api.close();
            store.close();
        }
    }

    private static boolean isZip(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 'P' && head[1] == 'K';
        }
    }
}
