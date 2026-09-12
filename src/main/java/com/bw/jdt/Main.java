package com.bw.jdt;

import java.util.Arrays;
import java.util.Locale;

/** Single jar entry point dispatching to server, client or the test data generator. */
public final class Main {

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            System.exit(2);
            return;
        }
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        switch (argv[0].toLowerCase(Locale.ROOT)) {
            case "server" -> com.bw.jdt.server.ServerMain.main(rest);
            case "client" -> com.bw.jdt.client.ClientMain.main(rest);
            case "gen", "generate" -> com.bw.jdt.tools.GenerateTestArchives.main(rest);
            case "help", "--help", "-h" -> usage();
            default -> {
                System.err.println("unknown command: " + argv[0]);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                jDeltaTransfer

                  java -jar jDeltaTransfer-all.jar server  [options]
                  java -jar jDeltaTransfer-all.jar client  <list|info|config|fetch|hash> [options]
                  java -jar jDeltaTransfer-all.jar gen     [options]

                Add --help after a command for its options.
                """);
    }
}
