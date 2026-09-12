package com.bw.jdt.core;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scratch space for intermediate decompression results. Everything created here is deleted
 * when the work dir is closed, so a failed ingest cannot leak gigabytes of temp data.
 */
public final class WorkDir implements Closeable {
    private final Path root;
    private final AtomicLong counter = new AtomicLong();

    private WorkDir(Path root) {
        this.root = root;
    }

    public static WorkDir createTemp(String prefix) throws IOException {
        return new WorkDir(Files.createTempDirectory(prefix));
    }

    public static WorkDir createTemp(Path parent, String prefix) throws IOException {
        Files.createDirectories(parent);
        return new WorkDir(Files.createTempDirectory(parent, prefix));
    }

    public Path root() {
        return root;
    }

    public Path newFile(String suffix) {
        return root.resolve(counter.incrementAndGet() + suffix);
    }

    /** Streams {@code writer} output into a fresh temp file and returns it as a source. */
    public ByteSource spill(IoConsumer<OutputStream> writer) throws IOException {
        Path f = newFile(".bin");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(f), 1 << 16)) {
            writer.accept(out);
        }
        return ByteSource.ofFile(f);
    }

    @Override
    public void close() {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clean work dir " + root, e);
        }
    }

    @FunctionalInterface
    public interface IoConsumer<T> {
        void accept(T t) throws IOException;
    }
}
