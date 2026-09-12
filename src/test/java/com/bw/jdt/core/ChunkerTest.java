package com.bw.jdt.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkerTest {

    private static List<ChunkRef> chunks(byte[] data, Chunker.Params params) throws IOException {
        List<ChunkRef> out = new ArrayList<>();
        new Chunker(params).split(new ByteArrayInputStream(data),
                (buf, off, len) -> out.add(new ChunkRef(Hashes.of(buf, off, len), len)));
        return out;
    }

    @Test
    void blockSizesStayWithinTheConfiguredLimits() throws IOException {
        Chunker.Params params = Chunker.Params.of(4096, 16384);
        byte[] data = new byte[3_000_000];
        new Random(1).nextBytes(data);
        List<ChunkRef> chunks = chunks(data, params);

        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            int len = chunks.get(i).length();
            assertTrue(len <= params.max(), "block " + i + " of " + len + " exceeds max " + params.max());
            if (i < chunks.size() - 1) {
                assertTrue(len >= params.min(), "block " + i + " of " + len + " below min " + params.min());
            }
            total += len;
        }
        assertEquals(data.length, total);
    }

    @Test
    void maxBlockSizeArgumentClampsTheDynamicSize() {
        Chunker.Params params = Chunker.Params.of(1 << 20, 32768);
        assertTrue(params.max() <= 32768, "hard limit must win over the desired average");
        assertTrue(params.avg() <= params.max());
    }

    /**
     * The point of content defined chunking: inserting bytes in the middle must not renumber
     * every following block, otherwise there would be no delta to speak of.
     */
    @Test
    void insertingBytesOnlyDisturbsNearbyBlocks() throws IOException {
        Chunker.Params params = Chunker.Params.of(8192, 65536);
        byte[] original = new byte[4_000_000];
        new Random(7).nextBytes(original);

        byte[] modified = new byte[original.length + 1000];
        int cut = 1_500_000;
        System.arraycopy(original, 0, modified, 0, cut);
        new Random(99).nextBytes(new byte[0]);
        for (int i = 0; i < 1000; i++) {
            modified[cut + i] = (byte) i;
        }
        System.arraycopy(original, cut, modified, cut + 1000, original.length - cut);

        List<ChunkRef> a = chunks(original, params);
        List<ChunkRef> b = chunks(modified, params);

        var setA = new java.util.HashSet<Hash>();
        a.forEach(c -> setA.add(c.hash()));
        long shared = b.stream().filter(c -> setA.contains(c.hash())).count();

        assertTrue(shared > b.size() * 0.9,
                "expected most blocks to survive the insertion, shared " + shared + " of " + b.size());
    }

    /** The push and the pull API must agree, they are used on the same data by both sides. */
    @Test
    void pushAndPullChunkingProduceTheSameBlocks() throws IOException {
        Chunker.Params params = Chunker.Params.of(8192, 65536);
        byte[] data = new byte[2_500_000];
        new Random(11).nextBytes(data);

        List<ChunkRef> pulled = chunks(data, params);

        List<ChunkRef> pushed = new ArrayList<>();
        Chunker chunker = new Chunker(params);
        try (var out = chunker.newOutputStream(
                (buf, off, len) -> pushed.add(new ChunkRef(Hashes.of(buf, off, len), len)))) {
            // Write in awkward slices to make sure buffering does not shift cut points.
            int pos = 0;
            int[] slices = {1, 7, 100_000, 3, 999_999, 65_536, 250_000};
            int i = 0;
            while (pos < data.length) {
                int n = Math.min(slices[i++ % slices.length], data.length - pos);
                out.write(data, pos, n);
                pos += n;
            }
        }
        assertEquals(pulled, pushed);
    }

    @Test
    void chunkingIsDeterministic() throws IOException {
        Chunker.Params params = Chunker.Params.of(8192, 65536);
        byte[] data = new byte[1_000_000];
        new Random(3).nextBytes(data);
        assertEquals(chunks(data, params), chunks(data, params));
    }
}
