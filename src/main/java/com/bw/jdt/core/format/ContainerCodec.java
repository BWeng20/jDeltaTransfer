package com.bw.jdt.core.format;

import com.bw.jdt.core.ByteSource;
import com.bw.jdt.core.ContainerFormat;
import com.bw.jdt.core.DecomposeLimits;
import com.bw.jdt.core.WorkDir;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Takes one archive container apart into its decompressed entry payloads and puts it back
 * together byte for byte.
 *
 * <p>The contract is deliberately strict: {@link #decompose} must only return a result when
 * {@link #rebuild} is guaranteed to reproduce the original bytes exactly. Implementations
 * verify that themselves (for deflate streams by probing which compression level reproduces
 * the original) and return {@code null} whenever they cannot. The caller then falls back to
 * treating the payload as an opaque blob, which is always correct.
 */
public interface ContainerCodec {

    ContainerFormat format();

    /**
     * @return the split of {@code src} into codec private metadata plus decompressed entry
     *         payloads, or {@code null} if this container cannot be reproduced exactly
     */
    Decomposition decompose(ByteSource src, WorkDir wd, DecomposeLimits limits) throws IOException;

    /**
     * Writes the original container bytes given the metadata and the payloads in order.
     *
     * @param threads how many independent re-encodes this codec may run at once; 1 means serial
     */
    void rebuild(byte[] meta, List<ByteSource> parts, OutputStream out, int threads) throws IOException;

    /**
     * @param meta  codec private description of headers, ordering and compression settings
     * @param parts decompressed payload of every entry, in the order the codec expects them back
     */
    record Decomposition(byte[] meta, List<ByteSource> parts) {
    }
}
