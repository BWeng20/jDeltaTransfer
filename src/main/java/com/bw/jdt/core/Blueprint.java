package com.bw.jdt.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The complete recipe for one archive version: how to rebuild it from content defined blocks.
 *
 * <p>Transferring a new version means transferring this blueprint plus only those blocks the
 * receiver does not already have from its older version.
 */
public record Blueprint(Hash archiveHash, long archiveSize, Chunker.Params chunkParams, Node root) {

    /** Distinct block hashes referenced anywhere in the tree, with their lengths. */
    public Map<Hash, Integer> distinctChunks() {
        Map<Hash, Integer> out = new LinkedHashMap<>();
        walk(root, n -> {
            if (n instanceof Node.Blob b) {
                for (ChunkRef c : b.chunks()) {
                    out.putIfAbsent(c.hash(), c.length());
                }
            }
        });
        return out;
    }

    public Set<Hash> chunkHashes() {
        return new LinkedHashSet<>(distinctChunks().keySet());
    }

    public Stats stats() {
        long[] blobs = {0};
        long[] containers = {0};
        long[] blobBytes = {0};
        long[] chunkCount = {0};
        walk(root, n -> {
            if (n instanceof Node.Blob b) {
                blobs[0]++;
                blobBytes[0] += b.size();
                chunkCount[0] += b.chunks().size();
            } else {
                containers[0]++;
            }
        });
        return new Stats(containers[0], blobs[0], chunkCount[0], distinctChunks().size(), blobBytes[0]);
    }

    public record Stats(long containers, long blobs, long chunkRefs, long distinctChunks, long blobBytes) {
    }

    private static void walk(Node root, java.util.function.Consumer<Node> visitor) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            visitor.accept(n);
            if (n instanceof Node.Container c) {
                // Push in reverse so children are visited in declaration order.
                for (int i = c.children().size() - 1; i >= 0; i--) {
                    stack.push(c.children().get(i));
                }
            }
        }
    }
}
