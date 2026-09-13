package com.bw.jdt.server;

import com.bw.jdt.Args;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.proto.Wire;
import com.bw.jdt.core.DecomposeLimits;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point of the server.
 *
 * <pre>
 * --archives DIR        directory holding the archive versions   (default: STORE/archives)
 * --store DIR           persistent state: hashes, blueprints, blocks (default: ./jdt-store)
 * --port N              transfer port                             (default: 8080)
 * --bind HOST           transfer bind address                     (default: 0.0.0.0)
 * --admin-port N        overview page and admin commands          (default: port + 1)
 * --admin-bind HOST     admin bind address                        (default: 127.0.0.1)
 * --no-admin            do not open the admin port at all
 * --max-block-size SZ   hard upper bound for any transferred block (default: 4MiB)
 * --avg-block-size SZ   target average block size                 (default: 64KiB)
 * --threads N           HTTP worker threads                       (default: cores)
 * --ingest-threads N    archives decomposed in parallel on scan   (default: cores/2, max 4)
 * --max-7z-size SZ      largest nested 7z opened up instead of        (default: 512MiB)
 *                       being carried as opaque blocks
 * --compress true|false compress the block stream                  (default: true)
 * --compress-level N    gzip level, 0 disables it                  (default: 1)
 * --verify true|false   rebuild-and-compare after each ingest      (default: true)
 * --force-reindex       discard blocks and blueprints, ingest again
 * --no-scan             do not ingest on startup
 * </pre>
 */
public final class ServerMain {

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

        Path storeDir = Paths.get(args.get("store", "jdt-store")).toAbsolutePath().normalize();
        Path archiveDir = Paths.get(args.get("archives", storeDir.resolve("archives").toString()))
                .toAbsolutePath().normalize();

        long maxBlock = args.getBytes("max-block-size", 4L << 20);
        long avgBlock = args.getBytes("avg-block-size", 64L << 10);
        if (maxBlock < 1024 || maxBlock > Integer.MAX_VALUE) {
            System.err.println("--max-block-size must be between 1KiB and 2GiB");
            System.exit(2);
            return;
        }
        Chunker.Params params = Chunker.Params.of((int) Math.min(avgBlock, maxBlock), (int) maxBlock);

        int port = args.getInt("port", 8080);
        String bind = args.get("bind", "0.0.0.0");
        int threads = args.getInt("threads", Math.max(2, Runtime.getRuntime().availableProcessors()));
        boolean verify = args.getBool("verify", true);
        boolean forceReindex = args.has("force-reindex");
        boolean scan = !args.has("no-scan");

        VersionStore.Log log = VersionStore.Log.STDOUT;
        log.info("store      " + storeDir);
        log.info("archives   " + archiveDir);
        log.info("block size min=" + params.min() + " avg=" + params.avg() + " max=" + params.max()
                + " (hard limit " + maxBlock + ")");

        long maxSevenZ = args.getBytes("max-7z-size", DecomposeLimits.DEFAULT_MAX_SEVEN_Z);
        log.info("nested 7z opened up to " + maxSevenZ + " bytes"
                + (maxSevenZ == DecomposeLimits.DEFAULT_MAX_SEVEN_Z ? " (default)" : ""));

        VersionStore store = VersionStore.open(storeDir, archiveDir, params, verify, forceReindex);
        store.setLimits(new DecomposeLimits(maxSevenZ));
        store.setIngestThreads(args.getInt("ingest-threads",
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                store.close();
            } catch (Exception ignored) {
                // Shutting down anyway.
            }
        }));

        if (scan) {
            log.info("scanning " + archiveDir + " ...");
            int n = store.scan(log);
            log.info("ingest done, " + n + " new version(s), " + store.versions().size() + " total");
        }

        int compressionLevel = args.getInt("compress-level",
                args.getBool("compress", true) ? Wire.DEFAULT_COMPRESSION_LEVEL : 0);
        log.info("transport compression " + (compressionLevel > 0
                ? "gzip level " + compressionLevel : "off"));

        // The administrative half lives on its own port so a firewall rule can keep it off the
        // network, and binds to loopback by default so that it is unreachable even without one.
        String adminBind = args.get("admin-bind", "127.0.0.1");
        int adminPort = args.has("no-admin")
                ? HttpApi.Endpoints.NO_ADMIN
                : args.getInt("admin-port", port + 1);

        HttpApi api;
        try {
            api = new HttpApi(store,
                    new HttpApi.Endpoints(bind, port, adminBind, adminPort),
                    (int) maxBlock, threads, compressionLevel, log);
        } catch (java.io.IOException e) {
            // A mistyped or unavailable bind address is an operator error, not a crash.
            System.err.println("cannot start: " + e.getMessage());
            System.exit(1);
            return;
        }
        api.start();
        String host = "0.0.0.0".equals(bind) ? "localhost" : bind;
        log.info("transfer port  http://" + host + ":" + api.port() + "/api/versions");
        if (api.adminPort() != HttpApi.Endpoints.NO_ADMIN) {
            log.info("admin port     http://" + adminBind + ":" + api.adminPort()
                    + "/  (overview page and /api/rescan)");
        } else {
            log.info("admin port     disabled");
        }
    }

    private static void usage() {
        System.out.println("""
                jDeltaTransfer server

                  --archives DIR        directory holding the archive versions (default: STORE/archives)
                  --store DIR           persistent hashes, blueprints and blocks (default: ./jdt-store)
                  --port N              transfer port (default: 8080)
                  --bind HOST           transfer bind address (default: 0.0.0.0)
                  --admin-port N        port for the overview page and /api/rescan
                                        (default: --port + 1)
                  --admin-bind HOST     admin bind address (default: 127.0.0.1, so the admin half
                                        is unreachable from elsewhere even without a firewall).
                                        Any local address works, so the admin port can sit on a
                                        separate management interface: --admin-bind 10.0.99.5
                  --no-admin            do not open the admin port at all
                  --max-block-size SZ   hard upper bound for any transferred block (default: 4MiB)
                  --avg-block-size SZ   target average block size (default: 64KiB)
                  --threads N           HTTP worker threads (default: number of cores)
                  --ingest-threads N    archives decomposed in parallel on scan (default: cores/2, max 4)
                  --max-7z-size SZ      largest nested 7z opened up rather than carried as opaque
                                        blocks (default: 512MiB). Raising it shrinks deltas but
                                        costs LZMA2 time on every rebuild, on the client too.
                  --compress true|false compress the block stream when the client accepts it
                                        (default: true). Blocks carry decompressed content, so
                                        without this the wire carries more than the archive's own
                                        compressed growth.
                  --compress-level N    gzip level; 0 disables it (default: 1). Level 1 measured
                                        78.9% of the original at 62 MB/s against level 6's 77.2%
                                        at 39 MB/s -- raise it only for text heavy archives.
                  --verify true|false   rebuild and compare after each ingest (default: true)
                  --force-reindex       discard blocks and blueprints and ingest again
                  --no-scan             do not ingest archives on startup

                Sizes accept suffixes: 4MiB, 64K, 1GB, 1048576.
                """);
    }
}
