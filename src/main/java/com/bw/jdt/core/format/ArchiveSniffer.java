package com.bw.jdt.core.format;

import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ContainerFormat;

import java.io.IOException;
import java.io.InputStream;

/** Detects the container format from magic bytes; file names are never trusted. */
public final class ArchiveSniffer {

    private static final byte[] ZIP_LOCAL = {'P', 'K', 0x03, 0x04};
    private static final byte[] ZIP_EMPTY = {'P', 'K', 0x05, 0x06};
    private static final byte[] CAB = {'M', 'S', 'C', 'F'};
    private static final byte[] SEVEN_Z = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    /** Smallest payload worth looking into at all. */
    public static final int MIN_CONTAINER_SIZE = 22;

    private ArchiveSniffer() {
    }

    public static ContainerFormat detect(ByteSource src) throws IOException {
        if (src.size() < MIN_CONTAINER_SIZE) {
            return null;
        }
        byte[] head = new byte[8];
        int n;
        try (InputStream in = src.openStream()) {
            n = in.readNBytes(head, 0, head.length);
        }
        return detect(head, n);
    }

    public static ContainerFormat detect(byte[] head, int len) {
        if (startsWith(head, len, ZIP_LOCAL) || startsWith(head, len, ZIP_EMPTY)) {
            return ContainerFormat.ZIP;
        }
        if (startsWith(head, len, CAB)) {
            return ContainerFormat.CAB;
        }
        if (startsWith(head, len, SEVEN_Z)) {
            return ContainerFormat.SEVEN_Z;
        }
        return null;
    }

    private static boolean startsWith(byte[] head, int len, byte[] magic) {
        if (len < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
