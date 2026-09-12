package com.bw.jdt.core;

/**
 * Policy knobs for how far decomposition is willing to go.
 *
 * <p>These must be identical on both sides. The client decomposes its local base version to
 * learn which blocks it can derive from it; if it used a different policy than the server used
 * for the target version, the two would produce different blocks for the same content and the
 * delta would collapse to a full transfer. Correctness is never at risk -- the client simply
 * downloads what it cannot derive -- but the saving is. That is why the limits travel inside the
 * blueprint rather than being configured separately on each side.
 *
 * @param maxSevenZBytes largest nested 7z, in bytes of the container itself, that is opened up
 *                       instead of being carried as opaque blocks
 */
public record DecomposeLimits(long maxSevenZBytes) {

    /**
     * 512 MiB. Chosen because 7z has to be re-encoded to be verified and rebuilt, and LZMA2
     * runs at single digit MB/s: at this size one rebuild already costs minutes, and the client
     * pays that on every delta transfer.
     */
    public static final long DEFAULT_MAX_SEVEN_Z = 512L << 20;

    public static final DecomposeLimits DEFAULT = new DecomposeLimits(DEFAULT_MAX_SEVEN_Z);

    public DecomposeLimits {
        if (maxSevenZBytes < 0) {
            throw new IllegalArgumentException("maxSevenZBytes must not be negative");
        }
    }
}
