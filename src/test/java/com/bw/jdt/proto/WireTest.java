package com.bw.jdt.proto;

import com.bw.jdt.core.Hash;
import com.bw.jdt.core.Hashes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTest {

    private static byte[] payload(int len, int fill) {
        byte[] b = new byte[len];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    @Test
    void framedBlocksRoundTripAndAreVerified() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        List<byte[]> sent = new ArrayList<>();
        try (Wire.BlockWriter w = new Wire.BlockWriter(bos, 1 << 20)) {
            for (int i = 1; i <= 5; i++) {
                byte[] p = payload(i * 1000, i);
                sent.add(p);
                w.write(Hashes.of(p), p);
            }
        }

        List<byte[]> received = new ArrayList<>();
        long bytes = Wire.readBlocks(new ByteArrayInputStream(bos.toByteArray()), 1 << 20,
                (hash, p) -> received.add(p));
        assertEquals(sent.size(), received.size());
        for (int i = 0; i < sent.size(); i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(sent.get(i), received.get(i));
        }
        assertEquals(sent.stream().mapToLong(b -> b.length).sum(), bytes);
    }

    @Test
    void writerRefusesBlocksAboveTheServerLimit() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Wire.BlockWriter w = new Wire.BlockWriter(bos, 4096);
        byte[] tooBig = payload(4097, 1);
        IOException e = assertThrows(IOException.class, () -> w.write(Hashes.of(tooBig), tooBig));
        assertTrue(e.getMessage().contains("exceeds the server limit"));
    }

    @Test
    void readerRefusesBlocksAboveTheNegotiatedLimit() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Wire.BlockWriter w = new Wire.BlockWriter(bos, 1 << 20)) {
            byte[] p = payload(9000, 7);
            w.write(Hashes.of(p), p);
        }
        // A client configured with a smaller limit must reject the oversized frame.
        assertThrows(IOException.class, () ->
                Wire.readBlocks(new ByteArrayInputStream(bos.toByteArray()), 4096, (h, p) -> {
                }));
    }

    @Test
    void corruptedPayloadIsDetectedByItsBlockHash() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] p = payload(2000, 3);
        try (Wire.BlockWriter w = new Wire.BlockWriter(bos, 1 << 20)) {
            w.write(Hashes.of(p), p);
        }
        byte[] stream = bos.toByteArray();
        stream[stream.length - 20] ^= 0x40;

        IOException e = assertThrows(IOException.class, () ->
                Wire.readBlocks(new ByteArrayInputStream(stream), 1 << 20, (h, x) -> {
                }));
        assertTrue(e.getMessage().contains("hash mismatch") || e.getMessage().contains("trailer"),
                "unexpected message: " + e.getMessage());
    }

    @Test
    void truncatedStreamIsDetected() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] p = payload(2000, 3);
        try (Wire.BlockWriter w = new Wire.BlockWriter(bos, 1 << 20)) {
            w.write(Hashes.of(p), p);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        byte[] cut = java.util.Arrays.copyOf(bos.toByteArray(), bos.size() - 5);
        assertThrows(IOException.class, () ->
                Wire.readBlocks(new ByteArrayInputStream(cut), 1 << 20, (h, x) -> {
                }));
    }

    @Test
    void hashListRoundTrips() throws IOException {
        List<Hash> hashes = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            hashes.add(Hashes.of(("block-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Wire.writeHashList(hashes, bos);
        assertEquals(hashes, Wire.readHashList(new ByteArrayInputStream(bos.toByteArray())));
    }
}
