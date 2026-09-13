package com.bw.jdt;

import com.bw.jdt.client.DeltaClient;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.proto.Wire;
import com.bw.jdt.server.HttpApi;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blocks carry decompressed content, which is what makes deduplication work but also means an
 * uncompressed stream puts more bytes on the wire than the archive's own compressed growth.
 * Compressing the framed stream fixes that; the per block hashes are over the uncompressed
 * payload, so none of the verification changes.
 */
class TransportCompressionTest {

    private static final int MAX_BLOCK = 1 << 20;

    private record Fixture(VersionStore store, HttpApi api, URI server, Path archives) {
    }

    private static Fixture serve(Path tmp, String sub, int level) throws Exception {
        Path archives = tmp.resolve("archives");
        if (!java.nio.file.Files.isDirectory(archives)) {
            GenerateTestArchives.main(new String[]{
                    "--out", archives.toString(), "--count", "2",
                    "--start-size", "16MB", "--growth", "0.1", "--threads", "2"});
        }
        VersionStore store = VersionStore.open(tmp.resolve("store-" + sub), archives,
                Chunker.Params.of(32768, MAX_BLOCK), true, false);
        store.scan(VersionStore.Log.STDOUT);
        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, level, VersionStore.Log.STDOUT);
        api.start();
        return new Fixture(store, api, URI.create("http://127.0.0.1:" + api.port()), archives);
    }

    @Test
    void compressionShrinksTheWireAndLeavesTheResultIntact(@TempDir Path tmp) throws Exception {
        Fixture f = serve(tmp, "on", Wire.DEFAULT_COMPRESSION_LEVEL);
        try {
            Path base = tmp.resolve("base.zip");
            Path plainOut = tmp.resolve("plain.zip");
            Path gzipOut = tmp.resolve("gzip.zip");

            long plainWire;
            long gzipWire;
            long contentBytes;
            try (DeltaClient off = new DeltaClient(f.server(), tmp.resolve("c1"), DeltaClient.Log.STDOUT)
                    .acceptCompression(false)) {
                off.fetchFull("archive-v01", base);
                var r = off.fetchDelta("archive-v02", base, plainOut);
                plainWire = r.wireBytes();
                contentBytes = r.blockBytes();
                assertEquals(contentBytes + framingOverhead(r.blocksRequested()), plainWire,
                        "uncompressed, the wire is the block content plus exactly the framing");
            }
            try (DeltaClient on = new DeltaClient(f.server(), tmp.resolve("c2"), DeltaClient.Log.STDOUT)) {
                var r = on.fetchDelta("archive-v02", base, gzipOut);
                gzipWire = r.wireBytes();
                assertEquals(contentBytes, r.blockBytes(),
                        "the same content must be delivered either way");
                assertTrue(r.compressionRatio() < 1.0,
                        "compressionRatio should report a shrink, was " + r.compressionRatio());
            }

            System.out.printf(Locale.ROOT, "content %d bytes -> wire %d uncompressed, %d gzip (%.1f%%)%n",
                    contentBytes, plainWire, gzipWire, gzipWire * 100.0 / plainWire);
            assertTrue(gzipWire < plainWire, "gzip must put fewer bytes on the wire");

            // Both paths must land on the same archive, verified against the published hash.
            assertEquals(Hashes.ofFile(f.archives().resolve("archive-v02.zip")),
                    Hashes.ofFile(plainOut));
            assertEquals(Hashes.ofFile(plainOut), Hashes.ofFile(gzipOut));
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /** A server told not to compress must serve raw even to a client that offers to accept gzip. */
    @Test
    void aServerWithCompressionOffServesRaw(@TempDir Path tmp) throws Exception {
        Fixture f = serve(tmp, "off", 0);
        try (DeltaClient client = new DeltaClient(f.server(), tmp.resolve("c"), DeltaClient.Log.STDOUT)) {
            // Whether the server compresses is observed from the bytes, not from its config: the
            // transfer port's config is minimal and does not carry the compression setting, and
            // the response header is what a client must go by in any case.
            assertTrue(client.config().at("/transportCompression").isMissingNode(),
                    "the transfer port must not advertise the server's compression setting");

            Path base = tmp.resolve("base.zip");
            client.fetchFull("archive-v01", base);
            var r = client.fetchDelta("archive-v02", base, tmp.resolve("out.zip"));
            assertEquals(r.blockBytes() + framingOverhead(r.blocksRequested()), r.wireBytes(),
                    "nothing should have been compressed");
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /** The full download path is framed the same way, so it compresses the same way. */
    @Test
    void fullDownloadsAreCompressedToo(@TempDir Path tmp) throws Exception {
        Fixture f = serve(tmp, "full", Wire.DEFAULT_COMPRESSION_LEVEL);
        try (DeltaClient off = new DeltaClient(f.server(), tmp.resolve("c1"), DeltaClient.Log.STDOUT)
                     .acceptCompression(false);
             DeltaClient on = new DeltaClient(f.server(), tmp.resolve("c2"), DeltaClient.Log.STDOUT)) {

            var plain = off.fetchFull("archive-v01", tmp.resolve("plain.zip"));
            var gzip = on.fetchFull("archive-v01", tmp.resolve("gzip.zip"));

            assertEquals(plain.blockBytes(), gzip.blockBytes());
            assertTrue(gzip.wireBytes() < plain.wireBytes(),
                    "a full download should compress as well: " + gzip.wireBytes()
                            + " vs " + plain.wireBytes());
            assertEquals(Hashes.ofFile(tmp.resolve("plain.zip")), Hashes.ofFile(tmp.resolve("gzip.zip")));
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    @Test
    void gzipHelperHonoursTheRequestedLevel() throws Exception {
        byte[] text = "the same short sentence over and over. ".repeat(4000)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long fast = gzipSize(text, 1);
        long best = gzipSize(text, 9);
        assertTrue(best < fast, "level 9 should beat level 1 on compressible text: "
                + best + " vs " + fast);
    }

    /**
     * Bytes the framing itself costs: a tag, a hash and a length per block, plus the trailer.
     * Worth pinning down, because it is the floor the wire can never go below uncompressed.
     */
    private static long framingOverhead(long blocks) {
        return blocks * (1 + 32 + 4) + (1 + 8 + 8);
    }

    private static long gzipSize(byte[] data, int level) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var out = Wire.gzip(bos, level)) {
            out.write(data);
        }
        return bos.size();
    }
}
