package com.bw.jdt.tools;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import com.bw.jdt.Args;
import com.bw.jdt.core.Fmt;
import com.bw.jdt.core.Hashes;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates a chain of test archive versions.
 *
 * <p>Every version is an outer ZIP that contains plain files plus nested ZIP, CAB and 7z
 * archives. Version {@code n+1} is derived from version {@code n}: most files are byte
 * identical, a few get a new revision that rewrites a small part of their content, a few are
 * dropped and new ones are appended until the target size is reached. That makes the chain
 * behave like successive releases of a real product rather than like unrelated random data.
 *
 * <pre>
 * --out DIR           output directory                    (default: ./archives)
 * --count N           number of versions                  (default: 10)
 * --start-size SZ     target size of version 1            (default: 1GB)
 * --growth F          relative growth per version         (default: 0.15)
 * --seed N            master seed                         (default: 20260912)
 * --change-rate F     fraction of files revised per step  (default: 0.03)
 * --remove-rate F     fraction of files dropped per step  (default: 0.01)
 * --threads N         versions generated in parallel      (default: min(cores, 4))
 * --deflate-level N   level for deflated entries          (default: 6)
 * --outer-format F    outermost container: zip, cab or 7z (default: zip)
 * </pre>
 */
public final class GenerateTestArchives {

    private static final int ZIP_BUCKETS = 4;
    private static final int CAB_BUCKETS = 3;
    private static final int SEVENZ_BUCKETS = 2;

    /** Every container format that may sit at the top level. */
    private static final List<String> OUTER_FORMATS = List.of("zip", "cab", "7z");

    private static final long MIN_FILE = 64L << 10;
    private static final long MAX_FILE = 16L << 20;

    /** Rough compressed size of text content, used to aim at the requested archive size. */
    private static final double TEXT_RATIO = 0.18;
    private static final double TEXT_SHARE = 0.30;

    enum Bucket {PLAIN, ZIP, CAB, SEVENZ}

    record FileSpec(long id, int revision, long size, Content.Kind kind, Bucket bucket, String name) {
        FileSpec revised() {
            return new FileSpec(id, revision + 1, size, kind, bucket, name);
        }

        FileSpec in(Bucket other) {
            return new FileSpec(id, revision, size, kind, other, name);
        }

        double estimatedArchiveBytes() {
            return kind == Content.Kind.TEXT ? size * TEXT_RATIO : size;
        }
    }

    public static void main(String[] argv) throws Exception {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        if (args.has("help")) {
            usage();
            return;
        }

        Path outDir = Paths.get(args.get("out", "archives")).toAbsolutePath().normalize();
        int count = args.getInt("count", 10);
        long startSize = args.getBytes("start-size", 1L << 30);
        double growth = args.getDouble("growth", 0.15);
        long seed = args.getBytes("seed", 20260912L);
        double changeRate = args.getDouble("change-rate", 0.03);
        double removeRate = args.getDouble("remove-rate", 0.01);
        int threads = args.getInt("threads", Math.min(4, Runtime.getRuntime().availableProcessors()));
        int level = args.getInt("deflate-level", 6);
        String outerFormat = args.get("outer-format", "zip").toLowerCase(Locale.ROOT);
        if (!OUTER_FORMATS.contains(outerFormat)) {
            System.err.println("--outer-format must be one of " + OUTER_FORMATS);
            System.exit(2);
            return;
        }

        Files.createDirectories(outDir);

        // Build the manifest chain first: it is cheap and lets every version be written in parallel.
        List<List<FileSpec>> manifests = new ArrayList<>(count);
        long[] targets = new long[count];
        long totalTarget = 0;
        List<FileSpec> prev = null;
        long nextId = 1;
        for (int v = 0; v < count; v++) {
            long target = Math.round(startSize * Math.pow(1 + growth, v));
            targets[v] = target;
            totalTarget += target;
            Random rnd = new Random(Content.mix(seed, v + 1, 0x1111));
            List<FileSpec> m = prev == null
                    ? new ArrayList<>()
                    : evolve(prev, rnd, changeRate, removeRate);
            nextId = grow(m, rnd, target, nextId);
            m.sort(Comparator.comparing(FileSpec::name));
            manifests.add(m);
            prev = m;
        }

        System.out.println("generating " + count + " versions into " + outDir);
        System.out.println("target sizes: " + describeTargets(targets));
        System.out.println("estimated total: " + Fmt.human(totalTarget)
                + "  (make sure the volume has room for it)");
        System.out.println("threads: " + threads);
        System.out.println();

        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int v = 0; v < count; v++) {
            final int version = v + 1;
            final List<FileSpec> manifest = manifests.get(v);
            futures.add(pool.submit((Callable<String>) () -> writeVersion(version, manifest, outDir, level, outerFormat)));
        }
        pool.shutdown();
        List<String> lines = new ArrayList<>();
        Exception failure = null;
        for (Future<String> f : futures) {
            try {
                lines.add(f.get());
            } catch (Exception e) {
                failure = e;
                System.err.println("version generation failed: " + e.getCause());
            }
        }
        System.out.println();
        lines.stream().sorted().forEach(System.out::println);
        System.out.println();
        System.out.println("done in " + Fmt.seconds(System.nanoTime() - t0));
        if (failure != null) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------- manifest chain

    private static List<FileSpec> evolve(List<FileSpec> prev, Random rnd,
                                         double changeRate, double removeRate) {
        List<FileSpec> next = new ArrayList<>(prev.size());
        for (FileSpec f : prev) {
            double r = rnd.nextDouble();
            if (r < removeRate) {
                continue;
            }
            next.add(r < removeRate + changeRate ? f.revised() : f);
        }
        return next;
    }

    /** Appends new files until the estimated archive size reaches {@code target}. */
    private static long grow(List<FileSpec> manifest, Random rnd, long target, long nextId) {
        double est = 0;
        Map<Bucket, Double> perBucket = new LinkedHashMap<>();
        for (FileSpec f : manifest) {
            est += f.estimatedArchiveBytes();
            perBucket.merge(f.bucket(), f.estimatedArchiveBytes(), Double::sum);
        }
        double cabCap = target * 0.10;
        double sevenZCap = Math.min(target * 0.04, 64L << 20);
        double zipCap = target * 0.18;

        while (est < target) {
            long id = nextId++;
            Content.Kind kind = rnd.nextDouble() < TEXT_SHARE ? Content.Kind.TEXT : Content.Kind.BINARY;
            long size = logUniform(rnd, MIN_FILE, Math.min(MAX_FILE, Math.max(MIN_FILE * 2, target / 40)));
            Bucket bucket = pickBucket(rnd, perBucket, zipCap, cabCap, sevenZCap);
            String ext = kind == Content.Kind.TEXT ? "txt" : (rnd.nextBoolean() ? "bin" : "dat");
            String name = String.format(Locale.ROOT, "f%07d.%s", id, ext);
            FileSpec f = new FileSpec(id, 0, size, kind, bucket, name);
            manifest.add(f);
            est += f.estimatedArchiveBytes();
            perBucket.merge(bucket, f.estimatedArchiveBytes(), Double::sum);
        }

        // Every version must actually exercise all three nested formats, even a small one.
        ensureCoverage(manifest, Bucket.ZIP, ZIP_BUCKETS * 2);
        ensureCoverage(manifest, Bucket.CAB, CAB_BUCKETS * 2);
        ensureCoverage(manifest, Bucket.SEVENZ, SEVENZ_BUCKETS * 2);
        return nextId;
    }

    /**
     * Moves the smallest plain files into {@code bucket} until it holds {@code want} files.
     * Deterministic, and stable across versions because the choice only depends on file size
     * and id, so files do not migrate between nested archives from one version to the next.
     */
    private static void ensureCoverage(List<FileSpec> manifest, Bucket bucket, int want) {
        long have = manifest.stream().filter(f -> f.bucket() == bucket).count();
        if (have >= want) {
            return;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < manifest.size(); i++) {
            if (manifest.get(i).bucket() == Bucket.PLAIN) {
                candidates.add(i);
            }
        }
        candidates.sort(Comparator
                .comparingLong((Integer i) -> manifest.get(i).size())
                .thenComparingLong(i -> manifest.get(i).id()));
        for (int k = 0; k < candidates.size() && have < want; k++, have++) {
            int idx = candidates.get(k);
            manifest.set(idx, manifest.get(idx).in(bucket));
        }
    }

    private static Bucket pickBucket(Random rnd, Map<Bucket, Double> used,
                                     double zipCap, double cabCap, double sevenZCap) {
        double r = rnd.nextDouble();
        if (r < 0.03 && used.getOrDefault(Bucket.SEVENZ, 0.0) < sevenZCap) {
            return Bucket.SEVENZ;
        }
        if (r < 0.11 && used.getOrDefault(Bucket.CAB, 0.0) < cabCap) {
            return Bucket.CAB;
        }
        if (r < 0.26 && used.getOrDefault(Bucket.ZIP, 0.0) < zipCap) {
            return Bucket.ZIP;
        }
        return Bucket.PLAIN;
    }

    private static long logUniform(Random rnd, long min, long max) {
        if (max <= min) {
            return min;
        }
        double lo = Math.log(min);
        double hi = Math.log(max);
        return (long) Math.exp(lo + rnd.nextDouble() * (hi - lo));
    }

    // ------------------------------------------------------------- generation

    private static String writeVersion(int version, List<FileSpec> manifest, Path outDir, int level,
                                       String outerFormat) throws IOException {
        String id = String.format(Locale.ROOT, "archive-v%02d", version);
        String ext = "." + outerFormat;
        Path target = outDir.resolve(id + ext);
        Path tmpDir = outDir.resolve(".tmp-" + id);
        Files.createDirectories(tmpDir);
        long t0 = System.nanoTime();
        try {
            Map<String, Path> nested = new LinkedHashMap<>();
            nested.putAll(buildNestedZips(manifest, tmpDir, level));
            nested.putAll(buildNestedCabs(manifest, tmpDir, level));
            nested.putAll(buildNestedSevenZ(manifest, tmpDir));

            Path part = outDir.resolve(id + ext + ".part");
            switch (outerFormat) {
                case "7z" -> writeOuterSevenZ(part, version, manifest, nested);
                case "cab" -> writeOuterCab(part, version, manifest, nested, level);
                default -> writeOuterZip(part, version, manifest, nested, level);
            }
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            long size = Files.size(target);
            String sha = Hashes.ofFile(target).hex();
            return String.format(Locale.ROOT, "  %s  %12s  %d files  sha256=%s  (%s)",
                    id, Fmt.human(size), manifest.size(), sha, Fmt.seconds(System.nanoTime() - t0));
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static void writeOuterZip(Path part, int version, List<FileSpec> manifest,
                                      Map<String, Path> nested, int level) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(part), 1 << 20))) {
            zip.setLevel(level);
            putDeflated(zip, "manifest.txt", renderManifest(version, manifest));
            for (FileSpec f : manifest) {
                if (f.bucket() == Bucket.PLAIN) {
                    putContent(zip, "content/" + f.name(), f, level);
                }
            }
            for (Map.Entry<String, Path> e : nested.entrySet()) {
                putStoredFile(zip, "nested/" + e.getKey(), e.getValue());
            }
        }
    }

    /** Same content, but with a cabinet as the outermost container. */
    private static void writeOuterCab(Path part, int version, List<FileSpec> manifest,
                                      Map<String, Path> nested, int level) throws IOException {
        byte[] manifestText = renderManifest(version, manifest);
        List<CabWriter.Entry> entries = new ArrayList<>();
        entries.add(cabEntry("manifest.txt", manifestText.length,
                () -> new java.io.ByteArrayInputStream(manifestText)));
        for (FileSpec f : manifest) {
            if (f.bucket() == Bucket.PLAIN) {
                entries.add(cabEntry("content/" + f.name(), f.size(),
                        () -> Content.open(f.id(), f.revision(), f.size(), f.kind())));
            }
        }
        for (Map.Entry<String, Path> e : nested.entrySet()) {
            Path file = e.getValue();
            entries.add(cabEntry("nested/" + e.getKey(), Files.size(file),
                    () -> Files.newInputStream(file)));
        }
        new CabWriter(level).write(part, entries);
    }

    private static CabWriter.Entry cabEntry(String name, long size, StreamSupplier content) {
        return new CabWriter.Entry() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public InputStream open() throws IOException {
                return content.open();
            }
        };
    }

    /**
     * Same content, but with 7z as the outermost container. Useful for checking that nothing in
     * the pipeline assumes the top level is a ZIP.
     */
    private static void writeOuterSevenZ(Path part, int version, List<FileSpec> manifest,
                                         Map<String, Path> nested) throws IOException {
        Files.deleteIfExists(part);
        try (SevenZOutputFile sz = new SevenZOutputFile(part.toFile())) {
            sz.setContentMethods(Collections.singletonList(new SevenZMethodConfiguration(SevenZMethod.LZMA2)));
            byte[] manifestText = renderManifest(version, manifest);
            addSevenZEntry(sz, "manifest.txt", manifestText.length, 0,
                    () -> new java.io.ByteArrayInputStream(manifestText));
            for (FileSpec f : manifest) {
                if (f.bucket() == Bucket.PLAIN) {
                    addSevenZEntry(sz, "content/" + f.name(), f.size(), f.id(),
                            () -> Content.open(f.id(), f.revision(), f.size(), f.kind()));
                }
            }
            for (Map.Entry<String, Path> e : nested.entrySet()) {
                Path file = e.getValue();
                addSevenZEntry(sz, "nested/" + e.getKey(), Files.size(file), 0,
                        () -> Files.newInputStream(file));
            }
            sz.finish();
        }
    }

    private interface StreamSupplier {
        InputStream open() throws IOException;
    }

    private static void addSevenZEntry(SevenZOutputFile sz, String name, long size, long id,
                                       StreamSupplier content) throws IOException {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(name);
        entry.setDirectory(false);
        entry.setSize(size);
        entry.setHasStream(true);
        entry.setLastModifiedDate(new java.util.Date(1_700_000_000_000L + id));
        sz.putArchiveEntry(entry);
        try (InputStream in = content.open()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                sz.write(buf, 0, n);
            }
        }
        sz.closeArchiveEntry();
    }

    private static Map<String, Path> buildNestedZips(List<FileSpec> manifest, Path tmpDir, int level)
            throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        Map<Integer, List<FileSpec>> buckets = split(manifest, Bucket.ZIP, ZIP_BUCKETS);
        for (Map.Entry<Integer, List<FileSpec>> e : buckets.entrySet()) {
            String name = String.format(Locale.ROOT, "data-%02d.zip", e.getKey() + 1);
            Path p = tmpDir.resolve(name);
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(p), 1 << 20))) {
                zip.setLevel(level);
                for (FileSpec f : e.getValue()) {
                    putContent(zip, f.name(), f, level);
                }
            }
            out.put(name, p);
        }
        return out;
    }

    private static Map<String, Path> buildNestedCabs(List<FileSpec> manifest, Path tmpDir, int level)
            throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        Map<Integer, List<FileSpec>> buckets = split(manifest, Bucket.CAB, CAB_BUCKETS);
        CabWriter writer = new CabWriter(level);
        for (Map.Entry<Integer, List<FileSpec>> e : buckets.entrySet()) {
            String name = String.format(Locale.ROOT, "libs-%02d.cab", e.getKey() + 1);
            Path p = tmpDir.resolve(name);
            List<CabWriter.Entry> entries = new ArrayList<>();
            for (FileSpec f : e.getValue()) {
                entries.add(new CabWriter.Entry() {
                    @Override
                    public String name() {
                        return f.name();
                    }

                    @Override
                    public long size() {
                        return f.size();
                    }

                    @Override
                    public InputStream open() {
                        return Content.open(f.id(), f.revision(), f.size(), f.kind());
                    }
                });
            }
            writer.write(p, entries);
            out.put(name, p);
        }
        return out;
    }

    private static Map<String, Path> buildNestedSevenZ(List<FileSpec> manifest, Path tmpDir)
            throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        Map<Integer, List<FileSpec>> buckets = split(manifest, Bucket.SEVENZ, SEVENZ_BUCKETS);
        for (Map.Entry<Integer, List<FileSpec>> e : buckets.entrySet()) {
            String name = String.format(Locale.ROOT, "payload-%02d.7z", e.getKey() + 1);
            Path p = tmpDir.resolve(name);
            Files.deleteIfExists(p);
            try (SevenZOutputFile sz = new SevenZOutputFile(p.toFile())) {
                sz.setContentMethods(Collections.singletonList(new SevenZMethodConfiguration(SevenZMethod.LZMA2)));
                for (FileSpec f : e.getValue()) {
                    SevenZArchiveEntry entry = new SevenZArchiveEntry();
                    entry.setName(f.name());
                    entry.setDirectory(false);
                    entry.setSize(f.size());
                    entry.setHasStream(true);
                    entry.setLastModifiedDate(new java.util.Date(1_700_000_000_000L + f.id()));
                    sz.putArchiveEntry(entry);
                    try (InputStream in = Content.open(f.id(), f.revision(), f.size(), f.kind())) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            sz.write(buf, 0, n);
                        }
                    }
                    sz.closeArchiveEntry();
                }
                sz.finish();
            }
            out.put(name, p);
        }
        return out;
    }

    /**
     * Distributes the files of one bucket type over a fixed number of nested archives, keyed by
     * file name so that a file stays in the same nested archive across versions.
     */
    private static Map<Integer, List<FileSpec>> split(List<FileSpec> manifest, Bucket bucket, int buckets) {
        Map<Integer, List<FileSpec>> out = new LinkedHashMap<>();
        for (int i = 0; i < buckets; i++) {
            out.put(i, new ArrayList<>());
        }
        for (FileSpec f : manifest) {
            if (f.bucket() == bucket) {
                int idx = Math.floorMod(f.name().hashCode(), buckets);
                out.get(idx).add(f);
            }
        }
        out.values().forEach(l -> l.sort(Comparator.comparing(FileSpec::name)));
        out.entrySet().removeIf(e -> e.getValue().isEmpty());
        return out;
    }

    // -------------------------------------------------------------- zip entry

    private static void putContent(ZipOutputStream zip, String name, FileSpec f, int level)
            throws IOException {
        if (f.kind() == Content.Kind.BINARY) {
            // Random payload does not compress; storing it keeps generation and ingest fast
            // and mirrors how real archives treat already compressed members.
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(f.size());
            entry.setCompressedSize(f.size());
            entry.setCrc(Content.crc32(f.id(), f.revision(), f.size(), f.kind()));
            entry.setTime(1_700_000_000_000L + f.id());
            zip.putNextEntry(entry);
        } else {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.DEFLATED);
            entry.setTime(1_700_000_000_000L + f.id());
            zip.putNextEntry(entry);
        }
        try (InputStream in = Content.open(f.id(), f.revision(), f.size(), f.kind())) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
    }

    private static void putDeflated(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        entry.setTime(1_700_000_000_000L);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void putStoredFile(ZipOutputStream zip, String name, Path file) throws IOException {
        long size = Files.size(file);
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        entry.setTime(1_700_000_000_000L);
        zip.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static byte[] renderManifest(int version, List<FileSpec> manifest) {
        StringBuilder sb = new StringBuilder(manifest.size() * 48);
        sb.append("# jDeltaTransfer test archive, version ").append(version).append('\n');
        sb.append("# name\tsize\trevision\tkind\tcontainer\n");
        for (FileSpec f : manifest) {
            sb.append(f.name()).append('\t').append(f.size()).append('\t').append(f.revision())
                    .append('\t').append(f.kind()).append('\t').append(f.bucket()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String describeTargets(long[] targets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Fmt.human(targets[i]));
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                jDeltaTransfer test archive generator

                  --out DIR           output directory (default: ./archives)
                  --count N           number of versions (default: 10)
                  --start-size SZ     target size of version 1 (default: 1GB)
                  --growth F          relative growth per version (default: 0.15)
                  --seed N            master seed (default: 20260912)
                  --change-rate F     fraction of files revised per version (default: 0.03)
                  --remove-rate F     fraction of files dropped per version (default: 0.01)
                  --threads N         versions generated in parallel (default: min(cores, 4))
                  --deflate-level N   level for deflated entries (default: 6)
                  --outer-format F    outermost container: zip, cab or 7z (default: zip)

                Each version is an outer ZIP with plain files plus nested ZIP, CAB and 7z archives.
                """);
    }
}
