package com.bw.jdt.core;

/** One content defined block: its SHA-256 and its (dynamic) length. */
public record ChunkRef(Hash hash, int length) {
}
