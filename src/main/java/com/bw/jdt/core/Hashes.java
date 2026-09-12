package com.bw.jdt.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers. SHA-256 is the single hash function used everywhere in this project. */
public final class Hashes {
    public static final String ALGORITHM = "SHA-256";

    private Hashes() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    public static Hash of(byte[] data) {
        return of(data, 0, data.length);
    }

    public static Hash of(byte[] data, int off, int len) {
        MessageDigest md = newDigest();
        md.update(data, off, len);
        return Hash.wrap(md.digest());
    }

    public static Hash of(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return Hash.wrap(md.digest());
    }

    public static Hash ofFile(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return of(in);
        }
    }

    public static Hash of(ByteSource src) throws IOException {
        try (InputStream in = src.openStream()) {
            return of(in);
        }
    }
}
