package com.bw.jdt.proto;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * TLS setup shared by the server and the client.
 *
 * <p>What TLS buys here is a confidential channel and proof that the client reached the intended
 * server. It is emphatically <em>not</em> access control: without
 * {@link ServerConfig#requireClientCert()} anyone who can reach the port still gets every archive.
 * Encryption and authorisation are separate problems and are kept separate here.
 *
 * <p>There is deliberately no way to turn certificate or hostname verification off. A switch like
 * that is used once in a hurry and then never removed, and it makes the whole exercise theatre.
 * A self signed or privately issued certificate is supported the correct way instead: point the
 * peer at a truststore holding it, which pins that one certificate and is stronger than trusting
 * every public certificate authority.
 */
public final class Tls {

    /** TLS 1.2 is the floor; everything older has known problems and no reason to be offered. */
    public static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private Tls() {
    }

    /**
     * Server side TLS.
     *
     * @param keystore           holds the server's certificate and private key; exactly one key
     *                           entry, since no alias is selected
     * @param keystorePassword   password of the keystore and of the key inside it
     * @param truststore         certificates of accepted clients; only needed for mutual TLS
     * @param truststorePassword password of the truststore
     * @param requireClientCert  demand a client certificate, turning TLS into access control
     */
    public record ServerConfig(Path keystore, char[] keystorePassword,
                               Path truststore, char[] truststorePassword,
                               boolean requireClientCert) {

        public ServerConfig {
            if (keystore == null) {
                throw new IllegalArgumentException("a TLS keystore is required");
            }
            if (requireClientCert && truststore == null) {
                throw new IllegalArgumentException(
                        "requiring a client certificate needs a truststore to validate it against");
            }
        }
    }

    public static SSLContext serverContext(ServerConfig cfg) throws IOException {
        KeyManager[] km = keyManagers(cfg.keystore(), cfg.keystorePassword());
        TrustManager[] tm = cfg.truststore() == null
                ? null
                : trustManagers(cfg.truststore(), cfg.truststorePassword());
        return context(km, tm);
    }

    /**
     * Client side TLS.
     *
     * @param truststore certificates the client will accept, or {@code null} for the JVM default
     *                   set of public authorities
     * @param keystore   the client's own certificate, for a server that demands one; may be null
     */
    public static SSLContext clientContext(Path truststore, char[] truststorePassword,
                                           Path keystore, char[] keystorePassword)
            throws IOException {
        KeyManager[] km = keystore == null ? null : keyManagers(keystore, keystorePassword);
        TrustManager[] tm = truststore == null ? null : trustManagers(truststore, truststorePassword);
        if (km == null && tm == null) {
            return null; // Nothing custom: let HttpClient use the JVM default context.
        }
        return context(km, tm);
    }

    private static SSLContext context(KeyManager[] km, TrustManager[] tm) throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(km, tm, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot set up TLS: " + e.getMessage(), e);
        }
    }

    private static KeyManager[] keyManagers(Path file, char[] password) throws IOException {
        try {
            KeyStore store = load(file, password);
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            return kmf.getKeyManagers();
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot read the key from " + file + ": " + e.getMessage()
                    + ". The keystore and key passwords must match.", e);
        }
    }

    private static TrustManager[] trustManagers(Path file, char[] password) throws IOException {
        try {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(load(file, password));
            return tmf.getTrustManagers();
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot read the truststore " + file + ": " + e.getMessage(), e);
        }
    }

    private static KeyStore load(Path file, char[] password) throws IOException,
            GeneralSecurityException {
        if (!Files.isReadable(file)) {
            throw new IOException("keystore not readable: " + file);
        }
        // Lets the type follow from the file itself, so PKCS12 and JKS both just work.
        return KeyStore.getInstance(file.toFile(), password);
    }

    /**
     * Reads a password, preferring the environment over the command line.
     *
     * <p>A password in an argument is visible to anyone who can list processes, so the env var
     * wins when both are given and is what the documentation recommends.
     *
     * @return the password, or {@code null} when neither source provides one
     */
    public static char[] password(String fromArgument, String envName) {
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv.toCharArray();
        }
        return fromArgument == null || fromArgument.isEmpty() ? null : fromArgument.toCharArray();
    }
}
