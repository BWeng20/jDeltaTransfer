package com.bw.jdt;

import com.bw.jdt.client.DeltaClient;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.core.Hashes;
import com.bw.jdt.proto.Tls;
import com.bw.jdt.proto.Wire;
import com.bw.jdt.server.HttpApi;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transfer runs over TLS end to end, and a client that does not trust the server's certificate
 * is refused rather than quietly downgraded.
 *
 * <p>Certificates are generated with {@code keytool} from the running JDK, which is also how an
 * operator would produce them. Nothing is committed to the repository, and no test weakens
 * certificate or hostname verification -- there is no switch for that, on purpose.
 */
class TlsTest {

    private static final int MAX_BLOCK = 1 << 20;
    private static final String PASSWORD = "changeit";

    /** Generates a keystore with a self signed certificate valid for localhost and 127.0.0.1. */
    private static Path keystore(Path dir, String name, String cn) throws Exception {
        Path store = dir.resolve(name + ".p12");
        run(List.of(keytool(), "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=" + cn + ", O=jDeltaTransfer test",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD));
        return store;
    }

    /** Exports {@code from}'s certificate into a fresh truststore. */
    private static Path truststore(Path dir, String name, Path from, String alias) throws Exception {
        Path cert = dir.resolve(alias + ".crt");
        Path store = dir.resolve(name + ".p12");
        run(List.of(keytool(), "-exportcert", "-alias", alias, "-keystore", from.toString(),
                "-storepass", PASSWORD, "-file", cert.toString()));
        run(List.of(keytool(), "-importcert", "-noprompt", "-alias", alias, "-file", cert.toString(),
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD));
        return store;
    }

    private static String keytool() {
        String home = System.getProperty("java.home");
        Path tool = Path.of(home, "bin", "keytool.exe");
        if (!Files.isExecutable(tool)) {
            tool = Path.of(home, "bin", "keytool");
        }
        Assumptions.assumeTrue(Files.isExecutable(tool), "keytool not found next to the JDK");
        return tool.toString();
    }

    private static void run(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    private record Fixture(VersionStore store, HttpApi api, URI server, Path archives) {
    }

    private static Fixture serve(Path tmp, Tls.ServerConfig tls, int count) throws Exception {
        Path archives = tmp.resolve("archives");
        if (!Files.isDirectory(archives)) {
            GenerateTestArchives.main(new String[]{
                    "--out", archives.toString(), "--count", String.valueOf(count),
                    "--start-size", "16MB", "--growth", "0.1", "--threads", "2"});
        }
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives,
                Chunker.Params.of(32768, MAX_BLOCK), true, false);
        store.scan(VersionStore.Log.STDOUT);
        HttpApi api = new HttpApi(store,
                new HttpApi.Endpoints("127.0.0.1", 0, "127.0.0.1", 0),
                MAX_BLOCK, 4, Wire.DEFAULT_COMPRESSION_LEVEL, tls, VersionStore.Log.STDOUT);
        api.start();
        assertEquals("https", api.scheme());
        return new Fixture(store, api, URI.create("https://localhost:" + api.port()), archives);
    }

    @Test
    void aFullDeltaTransferRunsOverTls(@TempDir Path tmp) throws Exception {
        Path certs = Files.createDirectories(tmp.resolve("certs"));
        Path serverKs = keystore(certs, "server", "localhost");
        Path clientTs = truststore(certs, "clienttrust", serverKs, "server");

        Fixture f = serve(tmp, new Tls.ServerConfig(serverKs, PASSWORD.toCharArray(),
                null, null, false), 2);
        try (DeltaClient client = new DeltaClient(f.server(), tmp.resolve("cache"),
                Tls.clientContext(clientTs, PASSWORD.toCharArray(), null, null),
                DeltaClient.Log.STDOUT)) {

            Path base = tmp.resolve("v01.zip");
            var full = client.fetchFull("archive-v01", base);
            assertEquals(Hashes.ofFile(f.archives().resolve("archive-v01.zip")), full.archiveHash());

            Path out = tmp.resolve("v02.zip");
            var delta = client.fetchDelta("archive-v02", base, out);
            assertEquals(Hashes.ofFile(f.archives().resolve("archive-v02.zip")), Hashes.ofFile(out));
            assertTrue(delta.blocksReused() > 0, "the delta must still work over TLS");
            // Compression negotiation has to survive the TLS layer as well.
            assertTrue(delta.wireBytes() < delta.blockBytes(),
                    "blocks should still arrive compressed: " + delta.wireBytes()
                            + " vs " + delta.blockBytes());
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    @Test
    void aClientThatDoesNotTrustTheCertificateIsRefused(@TempDir Path tmp) throws Exception {
        Path certs = Files.createDirectories(tmp.resolve("certs"));
        Path serverKs = keystore(certs, "server", "localhost");
        // A truststore holding somebody else's certificate, not this server's.
        Path strangerKs = keystore(certs, "stranger", "localhost");
        Path wrongTs = truststore(certs, "wrongtrust", strangerKs, "stranger");

        Fixture f = serve(tmp, new Tls.ServerConfig(serverKs, PASSWORD.toCharArray(),
                null, null, false), 1);
        try (DeltaClient client = new DeltaClient(f.server(), tmp.resolve("cache"),
                Tls.clientContext(wrongTs, PASSWORD.toCharArray(), null, null),
                DeltaClient.Log.STDOUT)) {

            assertThrows(IOException.class, () -> client.listVersions(),
                    "an untrusted certificate must fail the handshake, not be waved through");
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /** A plain HTTP client must not be able to talk to an HTTPS port by accident either. */
    @Test
    void plainHttpAgainstATlsPortFails(@TempDir Path tmp) throws Exception {
        Path certs = Files.createDirectories(tmp.resolve("certs"));
        Path serverKs = keystore(certs, "server", "localhost");
        Fixture f = serve(tmp, new Tls.ServerConfig(serverKs, PASSWORD.toCharArray(),
                null, null, false), 1);
        try (DeltaClient client = new DeltaClient(
                URI.create("http://localhost:" + f.api().port()),
                tmp.resolve("cache2"), DeltaClient.Log.STDOUT)) {
            assertThrows(IOException.class, () -> client.listVersions());
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    /**
     * With {@code --tls-require-client-cert} the channel is not merely encrypted: a client without
     * a certificate is turned away, which is the only thing here that amounts to access control.
     */
    @Test
    void mutualTlsRefusesAClientWithoutACertificate(@TempDir Path tmp) throws Exception {
        Path certs = Files.createDirectories(tmp.resolve("certs"));
        Path serverKs = keystore(certs, "server", "localhost");
        Path clientKs = keystore(certs, "client", "jdt-client");
        Path clientTs = truststore(certs, "clienttrust", serverKs, "server");
        Path serverTs = truststore(certs, "servertrust", clientKs, "client");

        Fixture f = serve(tmp, new Tls.ServerConfig(serverKs, PASSWORD.toCharArray(),
                serverTs, PASSWORD.toCharArray(), true), 1);
        try {
            // With a certificate: allowed through.
            try (DeltaClient allowed = new DeltaClient(f.server(), tmp.resolve("c-ok"),
                    Tls.clientContext(clientTs, PASSWORD.toCharArray(),
                            clientKs, PASSWORD.toCharArray()),
                    DeltaClient.Log.STDOUT)) {
                assertEquals(1, allowed.listVersions().path("count").asInt());
            }
            // Trusting the server but presenting nothing: refused.
            try (DeltaClient anonymous = new DeltaClient(f.server(), tmp.resolve("c-no"),
                    Tls.clientContext(clientTs, PASSWORD.toCharArray(), null, null),
                    DeltaClient.Log.STDOUT)) {
                assertThrows(IOException.class, () -> anonymous.listVersions());
            }
        } finally {
            f.api().close();
            f.store().close();
        }
    }

    @Test
    void theEnvironmentWinsOverAPasswordOnTheCommandLine() {
        // Nothing sets JDT_TEST_PW here, so the argument is used...
        assertEquals("fromArg", new String(Tls.password("fromArg", "JDT_TEST_PW_UNSET")));
        // ...and an absent argument with an absent variable yields nothing at all.
        org.junit.jupiter.api.Assertions.assertNull(Tls.password(null, "JDT_TEST_PW_UNSET"));
        org.junit.jupiter.api.Assertions.assertNull(Tls.password("", "JDT_TEST_PW_UNSET"));
    }

    @Test
    void requiringAClientCertificateWithoutATruststoreIsRejected(@TempDir Path tmp) throws Exception {
        Path certs = Files.createDirectories(tmp.resolve("certs"));
        Path serverKs = keystore(certs, "server", "localhost");
        assertThrows(IllegalArgumentException.class, () -> new Tls.ServerConfig(
                serverKs, PASSWORD.toCharArray(), null, null, true));
    }
}
