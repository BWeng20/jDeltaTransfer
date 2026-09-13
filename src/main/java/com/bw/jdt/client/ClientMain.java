package com.bw.jdt.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.bw.jdt.Args;
import com.bw.jdt.core.Fmt;
import com.bw.jdt.core.Hashes;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

/**
 * Entry point of the client.
 *
 * <pre>
 * list                      --server URL
 * info    --version ID      --server URL
 * fetch   --version ID --out FILE [--base OLDFILE] [--server URL] [--cache DIR]
 * hash    --file FILE
 * </pre>
 *
 * <p>{@code fetch} without {@code --base} downloads the full archive, with {@code --base} only
 * the blocks missing from the older local version. Both verify the SHA-256 of the result
 * against the hash the server published before the file is moved into place.
 */
public final class ClientMain {

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0 || "--help".equals(argv[0]) || "help".equals(argv[0])) {
            usage();
            return;
        }
        String command = argv[0].toLowerCase(Locale.ROOT);
        Args args = Args.parse(Arrays.copyOfRange(argv, 1, argv.length));

        if ("hash".equals(command)) {
            Path file = Paths.get(args.require("file"));
            System.out.println(Hashes.ofFile(file).hex() + "  " + file);
            return;
        }

        URI server = URI.create(args.get("server", "http://localhost:8080"));
        Path cache = Paths.get(args.get("cache", "jdt-client-cache")).toAbsolutePath().normalize();
        DeltaClient.Log log = DeltaClient.Log.STDOUT;

        try (DeltaClient client = new DeltaClient(server, cache, log)
                .keepBaseCache(args.has("keep-base-cache"))
                .indexThreads(args.getInt("index-threads",
                        Math.min(8, Runtime.getRuntime().availableProcessors())))
                .rebuildThreads(args.getInt("rebuild-threads",
                        Math.min(8, Runtime.getRuntime().availableProcessors())))
                .rebuildAs(parseRebuildAs(args.get("rebuild-as", "original")))
                .acceptCompression(!args.has("no-compress"))) {
            switch (command) {
                case "list" -> printList(client.listVersions());
                case "info" -> System.out.println(client.versionInfo(args.require("version")).toPrettyString());
                case "config" -> System.out.println(client.config().toPrettyString());
                case "fetch" -> fetch(client, args);
                default -> {
                    System.err.println("unknown command: " + command);
                    usage();
                    System.exit(2);
                }
            }
        }
    }

    private static void fetch(DeltaClient client, Args args) throws Exception {
        String version = args.require("version");
        Path out = Paths.get(args.require("out")).toAbsolutePath().normalize();
        String baseArg = args.get("base", null);

        long t0 = System.nanoTime();
        DeltaClient.TransferResult r;
        if (baseArg == null) {
            System.out.println("[client] full download of " + version);
            r = client.fetchFull(version, out);
        } else {
            Path baseFile = Paths.get(baseArg).toAbsolutePath().normalize();
            if (!Files.exists(baseFile)) {
                throw new IllegalArgumentException("base archive not found: " + baseFile);
            }
            System.out.println("[client] delta download of " + version + " against " + baseFile.getFileName());
            r = client.fetchDelta(version, baseFile, out);
        }
        long elapsed = System.nanoTime() - t0;

        System.out.println();
        System.out.println("  version            " + r.versionId());
        System.out.println("  archive size       " + Fmt.human(r.archiveSize()));
        System.out.println("  sha256 verified    " + r.archiveHash().hex());
        System.out.println("  mode               " + (r.delta() ? "delta" : "full"));
        if (r.delta()) {
            System.out.println("  blueprint          " + Fmt.human(r.blueprintBytes()));
            System.out.println("  blocks downloaded  " + r.blocksRequested() + " ("
                    + Fmt.human(r.blockBytes()) + ")");
            System.out.println("  blocks reused      " + r.blocksReused());
        } else {
            System.out.println("  blocks received    " + r.blocksRequested() + " ("
                    + Fmt.human(r.blockBytes()) + ")");
        }
        System.out.println("  transferred        " + Fmt.human(r.transferredBytes())
                + "  (" + Fmt.percent(r.savedFraction()) + " less than the full archive)");
        if (r.wireBytes() != r.blockBytes()) {
            System.out.println("  on the wire        " + Fmt.human(r.wireTotalBytes())
                    + "  (" + Fmt.percent(r.wireSavedFraction()) + " less; blocks compressed to "
                    + Fmt.percent(r.compressionRatio()) + ")");
        }
        System.out.println("  elapsed            " + Fmt.seconds(elapsed));
        if (r.delta()) {
            System.out.println("  phases             " + r.phases().describe());
        }
        System.out.println("  written to         " + out);
    }

    private static DeltaClient.RebuildAs parseRebuildAs(String value) {
        try {
            return DeltaClient.RebuildAs.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--rebuild-as must be one of original, zip, extract");
        }
    }

    private static void printList(JsonNode root) {
        System.out.printf(Locale.ROOT, "%-16s %14s  %-64s %s%n", "VERSION", "SIZE", "SHA-256", "BLOCKS");
        for (JsonNode v : root.path("versions")) {
            System.out.printf(Locale.ROOT, "%-16s %14s  %-64s %d/%d%n",
                    v.path("id").asText(),
                    Fmt.human(v.path("size").asLong()),
                    v.path("sha256").asText(),
                    v.path("blockRefs").asLong(),
                    v.path("distinctBlocks").asLong());
        }
    }

    private static void usage() {
        System.out.println("""
                jDeltaTransfer client

                  list                                          list versions and their hashes
                  info   --version ID                           details of one version
                  config                                        server block size limits
                  fetch  --version ID --out FILE [--base OLD]   download (delta if --base is given)
                  hash   --file FILE                            SHA-256 of a local file

                Common options:
                  --server URL   server base URL (default: http://localhost:8080)
                  --cache DIR    where the decomposed local base is cached (default: ./jdt-client-cache)
                  --keep-base-cache  copy the index to the new version instead of renaming it,
                                     keeping the old base indexed as well
                  --index-threads N    entries decomposed in parallel while indexing the base
                                       (default: min(cores, 8))
                  --rebuild-threads N  nested archives rebuilt in parallel; this is the dominant
                                       cost of a warm transfer (default: min(cores, 8))
                  --no-compress        do not ask the server to compress the block stream. Blocks
                                       carry decompressed content, so compression normally pays;
                                       turn it off on a link fast enough that gzip is the
                                       bottleneck (measured: 62 MB/s at the default level).
                  --rebuild-as M       original (default), zip or extract. Only "original"
                                       reproduces the archive byte for byte and can be checked
                                       against the published SHA-256. The other two write the same
                                       content in a cheaper shape, which is worth it for a solid
                                       7z: re-encoding one costs minutes per gigabyte and cannot
                                       be parallelised. Each member is still verified against the
                                       blueprint.
                """);
    }
}
