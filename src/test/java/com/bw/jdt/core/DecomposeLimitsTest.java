package com.bw.jdt.core;

import com.bw.jdt.tools.Content;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 7z size limit decides whether a nested container is opened up or carried as opaque blocks.
 * Both outcomes must rebuild the archive byte for byte; only the delta size differs.
 */
class DecomposeLimitsTest {

    private static Path writeSevenZ(Path target, long rawBytes) throws Exception {
        Files.deleteIfExists(target);
        try (SevenZOutputFile sz = new SevenZOutputFile(target.toFile())) {
            sz.setContentMethods(Collections.singletonList(new SevenZMethodConfiguration(SevenZMethod.LZMA2)));
            for (int i = 0; i < 3; i++) {
                long id = 7000 + i;
                long size = rawBytes / 3;
                SevenZArchiveEntry e = new SevenZArchiveEntry();
                e.setName("limited" + id + ".dat");
                e.setDirectory(false);
                e.setSize(size);
                e.setHasStream(true);
                e.setLastModifiedDate(new java.util.Date(1_700_000_000_000L + id));
                sz.putArchiveEntry(e);
                try (InputStream in = Content.open(id, 0, size, Content.Kind.BINARY)) {
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
        return target;
    }

    private static Blueprint decompose(Path archive, Path work, DecomposeLimits limits) throws Exception {
        try (ChunkStore store = ChunkStore.open(work.resolve("blocks-" + limits.maxSevenZBytes()));
             WorkDir wd = WorkDir.createTemp(work.resolve("wd"), "d-")) {
            Blueprint bp = new Decomposer(store, Chunker.Params.of(16384, 1 << 20), limits)
                    .decompose(ByteSource.ofFile(archive), wd);
            // Whatever the policy decided, the rebuild has to be exact.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WorkDir rwd = WorkDir.createTemp(work.resolve("wd"), "r-")) {
                new Reassembler(store, rwd).writeVerified(bp, out);
            }
            assertEquals(Hashes.ofFile(archive), Hashes.of(out.toByteArray()));
            return bp;
        }
    }

    @Test
    void aSevenZBelowTheLimitIsOpenedAndOneAboveItIsNot(@TempDir Path tmp) throws Exception {
        Path sevenZ = writeSevenZ(tmp.resolve("nested.7z"), 900_000);
        long packed = Files.size(sevenZ);
        assertTrue(packed > 1000, "test archive unexpectedly tiny: " + packed);

        Blueprint opened = decompose(sevenZ, tmp.resolve("a"), new DecomposeLimits(packed));
        assertInstanceOf(Node.Container.class, opened.root(),
                "at exactly the limit the 7z must still be opened");
        assertEquals(ContainerFormat.SEVEN_Z, ((Node.Container) opened.root()).format());

        Blueprint opaque = decompose(sevenZ, tmp.resolve("b"), new DecomposeLimits(packed - 1));
        assertInstanceOf(Node.Blob.class, opaque.root(),
                "one byte over the limit the 7z must be carried as opaque blocks");
    }

    @Test
    void theLimitTravelsInsideTheBlueprint(@TempDir Path tmp) throws Exception {
        Path sevenZ = writeSevenZ(tmp.resolve("nested.7z"), 300_000);
        DecomposeLimits limits = new DecomposeLimits(12345678L);
        Blueprint bp = decompose(sevenZ, tmp.resolve("c"), limits);

        Blueprint roundTripped = BlueprintCodec.fromGzipBytes(BlueprintCodec.toGzipBytes(bp));
        assertEquals(limits, roundTripped.limits(),
                "the client derives its decomposition policy from the blueprint");
    }

    /**
     * Blueprints written before the limits existed must keep working, otherwise upgrading the
     * server would silently invalidate every stored blueprint.
     */
    @Test
    void formatVersionOneBlueprintsStillReadAndDefaultTheLimits() throws Exception {
        byte[] payload = "an old blueprint".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Hash blockHash = Hashes.of(payload);
        Hash archiveHash = Hashes.of("whole archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            DataOutputStream out = new DataOutputStream(gz);
            out.write(new byte[]{'J', 'D', 'T', 'B', 'P'});
            out.writeByte(1);                       // format version 1: no limits field
            out.write(archiveHash.toBytes());
            out.writeLong(payload.length);
            out.writeInt(16384);
            out.writeInt(65536);
            out.writeInt(262144);
            out.writeByte(0);                       // blob node
            out.writeLong(payload.length);
            out.writeInt(1);
            out.write(blockHash.toBytes());
            out.writeInt(payload.length);
            out.flush();
        }

        Blueprint bp = BlueprintCodec.fromGzipBytes(raw.toByteArray());
        assertEquals(archiveHash, bp.archiveHash());
        assertEquals(DecomposeLimits.DEFAULT, bp.limits(),
                "a version 1 blueprint was produced under the default policy");
        assertInstanceOf(Node.Blob.class, bp.root());
    }
}
