package com.bw.jdt.core;

import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rebuilding the nested archives concurrently must not change a single byte of the result.
 * The nested containers are independent subtrees, but they are re-encoded with stateful codecs
 * (deflate, MSZIP with its preset dictionary chain, LZMA2), so this is worth pinning down.
 */
class ParallelRebuildTest {

    private static Blueprint decompose(Path archive, ChunkStore store, Path work) throws Exception {
        try (WorkDir wd = WorkDir.createTemp(work, "d-")) {
            return new Decomposer(store, Chunker.Params.of(65536, 4 << 20))
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

    @Test
    void parallelAndSerialRebuildsAreIdentical(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1",
                "--start-size", "24MB", "--threads", "2"});
        Path archive = archives.resolve("archive-v01.zip");

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"))) {
            Blueprint bp = decompose(archive, store, tmp.resolve("work"));

            long nested = bp.root() instanceof Node.Container c
                    ? c.children().stream().filter(n -> n instanceof Node.Container).count()
                    : 0;
            assertTrue(nested >= 2,
                    "the test archive must hold several nested containers, found " + nested);

            byte[] serial = rebuild(bp, store, tmp.resolve("work"), 1);
            byte[] parallel = rebuild(bp, store, tmp.resolve("work"), 8);

            assertArrayEquals(serial, parallel, "parallel rebuild changed the bytes");
            assertArrayEquals(Files.readAllBytes(archive), parallel);
        }
    }

    @Test
    void moreThreadsThanNestedArchivesIsHarmless(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1",
                "--start-size", "12MB", "--threads", "1"});
        Path archive = archives.resolve("archive-v01.zip");

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"))) {
            Blueprint bp = decompose(archive, store, tmp.resolve("work"));
            byte[] rebuilt = rebuild(bp, store, tmp.resolve("work"), 64);
            assertEquals(Hashes.ofFile(archive), Hashes.of(rebuilt));
        }
    }

    /** A failure inside one subtree must surface, not hang or be swallowed by the pool. */
    @Test
    void aMissingBlockStillFailsTheRebuild(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1",
                "--start-size", "12MB", "--threads", "1"});

        try (ChunkStore store = ChunkStore.open(tmp.resolve("blocks"))) {
            Blueprint bp = decompose(archives.resolve("archive-v01.zip"), store, tmp.resolve("work"));

            // An empty source has none of the blocks the blueprint needs.
            ChunkSource empty = new ChunkSource() {
                @Override
                public boolean contains(Hash hash) {
                    return false;
                }

                @Override
                public byte[] get(Hash hash) throws java.io.IOException {
                    throw new java.io.IOException("block missing: " + hash);
                }
            };
            try (WorkDir wd = WorkDir.createTemp(tmp.resolve("work"), "fail-")) {
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () ->
                        new Reassembler(empty, wd).withThreads(8)
                                .writeVerified(bp, java.io.OutputStream.nullOutputStream()));
            }
        }
    }
}
