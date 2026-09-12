package com.bw.jdt.server;

import com.bw.jdt.Args;
import com.bw.jdt.core.Chunker;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point of the server.
 *
 * <pre>
 * --archives DIR        directory holding the archive versions   (default: STORE/archives)
 * --store DIR           persistent state: hashes, blueprints, blocks (default: ./jdt-store)
 * --port N              HTTP port                                 (default: 8080)
 * --bind HOST           bind address                              (default: 0.0.0.0)
 * --max-block-size SZ   hard upper bound for any transferred block (default: 4MiB)
 * --avg-block-size SZ   target average block size                 (default: 64KiB)
 * --threads N           HTTP worker threads                       (default: cores)
 * --ingest-threads N    archives decomposed in parallel on scan   (default: cores/2, max 4)
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

        VersionStore store = VersionStore.open(storeDir, archiveDir, params, verify, forceReindex);
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

        HttpApi api = new HttpApi(store, bind, port, (int) maxBlock, threads, log);
        api.start();
        log.info("listening on http://" + ("0.0.0.0".equals(bind) ? "localhost" : bind) + ":" + api.port() + "/");
        log.info("version list: http://localhost:" + api.port() + "/api/versions");
    }

    private static void usage() {
        System.out.println("""
                jDeltaTransfer server

                  --archives DIR        directory holding the archive versions (default: STORE/archives)
                  --store DIR           persistent hashes, blueprints and blocks (default: ./jdt-store)
                  --port N              HTTP port (default: 8080)
                  --bind HOST           bind address (default: 0.0.0.0)
                  --max-block-size SZ   hard upper bound for any transferred block (default: 4MiB)
                  --avg-block-size SZ   target average block size (default: 64KiB)
                  --threads N           HTTP worker threads (default: number of cores)
                  --ingest-threads N    archives decomposed in parallel on scan (default: cores/2, max 4)
                  --verify true|false   rebuild and compare after each ingest (default: true)
                  --force-reindex       discard blocks and blueprints and ingest again
                  --no-scan             do not ingest archives on startup

                Sizes accept suffixes: 4MiB, 64K, 1GB, 1048576.
                """);
    }
}
