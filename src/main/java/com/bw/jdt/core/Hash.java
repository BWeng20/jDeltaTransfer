package com.bw.jdt.core;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Immutable 32 byte SHA-256 value with a cheap hashCode, used as key in the chunk store
 * and as the identity of every archive and block that travels over the wire.
 */
public final class Hash implements Comparable<Hash> {
    public static final int LENGTH = 32;

    private final byte[] bytes;
    private final int hash;

    private Hash(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("expected " + LENGTH + " bytes, got " + bytes.length);
        }
        this.bytes = bytes;
        // The first four bytes of a SHA-256 are already uniformly distributed.
        this.hash = ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    public static Hash of(byte[] raw) {
        return new Hash(raw.clone());
    }

    /** Takes ownership of {@code raw}; the caller must not modify it afterwards. */
    public static Hash wrap(byte[] raw) {
        return new Hash(raw);
    }

    public static Hash parse(String hex) {
        return new Hash(HexFormat.of().parseHex(hex));
    }

    public byte[] toBytes() {
        return bytes.clone();
    }

    public void writeTo(byte[] dst, int off) {
        System.arraycopy(bytes, 0, dst, off, LENGTH);
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Hash other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(Hash o) {
        return Arrays.compareUnsigned(bytes, o.bytes);
    }

    @Override
    public String toString() {
        return hex();
    }
}
