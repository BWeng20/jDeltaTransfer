package com.bw.jdt;

import com.bw.jdt.core.Chunker;
import com.bw.jdt.proto.Wire;
import com.bw.jdt.server.HttpApi;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The administrative half of the API lives on its own port so a firewall rule can keep it off the
 * network. The point of the split is that the transfer port does not merely refuse those
 * endpoints, it does not have them.
 */
class AdminPortTest {

    private static final int MAX_BLOCK = 1 << 20;

    private static VersionStore store(Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "1", "--start-size", "8MB", "--threads", "1"});
        VersionStore store = VersionStore.open(tmp.resolve("store"), archives,
                Chunker.Params.of(32768, MAX_BLOCK), true, false);
        store.scan(VersionStore.Log.STDOUT);
        return store;
    }

    private static HttpApi serve(VersionStore store, int adminPort) throws Exception {
        HttpApi api = new HttpApi(store,
                new HttpApi.Endpoints("127.0.0.1", 0, "127.0.0.1", adminPort),
                MAX_BLOCK, 4, Wire.DEFAULT_COMPRESSION_LEVEL, VersionStore.Log.STDOUT);
        api.start();
        return api;
    }

    private static int status(HttpClient http, URI uri, String method) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        HttpRequest req = "POST".equals(method)
                ? b.POST(HttpRequest.BodyPublishers.noBody()).build()
                : b.GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theTransferPortCarriesNoAdminEndpoints(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = serve(store, 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            URI transfer = URI.create("http://127.0.0.1:" + api.port());
            URI admin = URI.create("http://127.0.0.1:" + api.adminPort());
            assertNotEquals(api.port(), api.adminPort(), "the two ports must differ");

            // Administrative: only on the admin port.
            assertEquals(404, status(http, transfer.resolve("/api/rescan"), "POST"));
            assertEquals(200, status(http, admin.resolve("/api/rescan"), "POST"));
            assertEquals(404, status(http, transfer.resolve("/"), "GET"));
            assertEquals(200, status(http, admin.resolve("/"), "GET"));

            // What a client needs is on both, so the overview page's links work.
            for (URI b : new URI[]{transfer, admin}) {
                assertEquals(200, status(http, b.resolve("/api/versions"), "GET"));
                assertEquals(200, status(http, b.resolve("/api/config"), "GET"));
                assertEquals(200, status(http, b.resolve("/health"), "GET"));
                assertEquals(200, status(http, b.resolve("/api/versions/archive-v01"), "GET"));
            }
        } finally {
            api.close();
            store.close();
        }
    }

    /**
     * Both ports answer {@code /api/config}, but not with the same document: the client gets only
     * what it acts on, the operator gets everything. Store statistics in particular must not leak
     * onto the transfer port.
     */
    @Test
    void theTwoPortsAnswerConfigDifferently(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = serve(store, 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var client = mapper.readTree(body(http,
                    URI.create("http://127.0.0.1:" + api.port() + "/api/config")));
            var admin = mapper.readTree(body(http,
                    URI.create("http://127.0.0.1:" + api.adminPort() + "/api/config")));

            assertEquals(java.util.Set.of("hashAlgorithm", "maxBlockSize"),
                    fieldNames(client), "the client document must stay minimal");
            assertTrue(fieldNames(admin).containsAll(fieldNames(client)),
                    "the admin document must be a superset");
            assertTrue(fieldNames(admin).size() > fieldNames(client).size());

            for (String operational : new String[]{
                    "storedBlocks", "storedBlockBytes", "transportCompressionLevel",
                    "maxSevenZDecomposeSize", "blockSizeMin"}) {
                assertTrue(admin.has(operational), "admin should report " + operational);
                assertTrue(client.at("/" + operational).isMissingNode(),
                        operational + " must not reach the transfer port");
            }

            // The one field the transfer code actually reads has to agree on both.
            assertEquals(admin.path("maxBlockSize").asInt(), client.path("maxBlockSize").asInt());
        } finally {
            api.close();
            store.close();
        }
    }

    private static java.util.Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode n) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String body(HttpClient http, URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void theOverviewPageSaysWhichPortItIs(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = serve(store, 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            String page = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + api.adminPort() + "/")).GET().build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
            assertTrue(page.contains("admin port"), "the page should name itself");
            assertTrue(page.contains(Integer.toString(api.port())),
                    "and point at the transfer port clients should use");
            assertTrue(page.contains("archive-v01"));
        } finally {
            api.close();
            store.close();
        }
    }

    @Test
    void theAdminPortCanBeLeftClosedEntirely(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = serve(store, HttpApi.Endpoints.NO_ADMIN);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(HttpApi.Endpoints.NO_ADMIN, api.adminPort());
            URI transfer = URI.create("http://127.0.0.1:" + api.port());
            assertEquals(200, status(http, transfer.resolve("/api/versions"), "GET"));
            assertEquals(404, status(http, transfer.resolve("/api/rescan"), "POST"));
        } finally {
            api.close();
            store.close();
        }
    }

    /** The convenience constructors used across the tests open no admin port. */
    @Test
    void theShortConstructorOpensNoAdminPort(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = new HttpApi(store, "127.0.0.1", 0, MAX_BLOCK, 4, VersionStore.Log.STDOUT);
        api.start();
        try {
            assertEquals(HttpApi.Endpoints.NO_ADMIN, api.adminPort());
        } finally {
            api.close();
            store.close();
        }
    }

    /**
     * The admin port may live on any local address, not just loopback -- a management interface,
     * typically. Verified here with a second loopback address, which every host has.
     */
    @Test
    void theAdminPortCanSitOnADifferentAddress(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = new HttpApi(store,
                new HttpApi.Endpoints("127.0.0.1", 0, "127.0.0.2", 0),
                MAX_BLOCK, 4, Wire.DEFAULT_COMPRESSION_LEVEL, VersionStore.Log.STDOUT);
        api.start();
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(200, status(http,
                    URI.create("http://127.0.0.2:" + api.adminPort() + "/"), "GET"),
                    "the admin page must answer on the address it was bound to");
            // And not on the transfer address, even at the same port number.
            assertThrows(java.io.IOException.class, () -> status(http,
                    URI.create("http://127.0.0.1:" + api.adminPort() + "/"), "GET"));
        } finally {
            api.close();
            store.close();
        }
    }

    @Test
    void aBindAddressThatCannotBeUsedIsReportedClearly(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        try {
            // Unresolvable: .invalid is reserved by RFC 2606 and never resolves.
            var unresolvable = assertThrows(java.io.IOException.class, () -> new HttpApi(store,
                    new HttpApi.Endpoints("127.0.0.1", 0, "no-such-host.invalid", 0),
                    MAX_BLOCK, 4, Wire.DEFAULT_COMPRESSION_LEVEL, VersionStore.Log.STDOUT));
            assertTrue(unresolvable.getMessage().contains("admin")
                            && unresolvable.getMessage().contains("resolve"),
                    "message should name the role and the problem: " + unresolvable.getMessage());

            // Resolvable but not an address of this host.
            var notLocal = assertThrows(java.io.IOException.class, () -> new HttpApi(store,
                    new HttpApi.Endpoints("127.0.0.1", 0, "10.99.99.99", 0),
                    MAX_BLOCK, 4, Wire.DEFAULT_COMPRESSION_LEVEL, VersionStore.Log.STDOUT));
            assertTrue(notLocal.getMessage().contains("admin"),
                    "message should name the role: " + notLocal.getMessage());
        } finally {
            store.close();
        }
    }

    /** Binding admin to a wildcard is allowed but must not pass silently. */
    @Test
    void aWildcardAdminBindIsWarnedAbout(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        java.util.List<String> warnings = new java.util.ArrayList<>();
        VersionStore.Log log = new VersionStore.Log() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
        HttpApi api = new HttpApi(store,
                new HttpApi.Endpoints("127.0.0.1", 0, "0.0.0.0", 0),
                MAX_BLOCK, 4, Wire.DEFAULT_COMPRESSION_LEVEL, log);
        try {
            assertTrue(warnings.stream().anyMatch(w -> w.contains("every interface")),
                    "expected a warning, got " + warnings);
        } finally {
            api.close();
            store.close();
        }
    }

    /** A rescan on the admin port must still do its job, not just answer. */
    @Test
    void rescanOnTheAdminPortIngestsNewArchives(@TempDir Path tmp) throws Exception {
        VersionStore store = store(tmp);
        HttpApi api = serve(store, 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(1, store.versions().size());

            // Drop a second archive in after startup.
            Path staging = tmp.resolve("staging");
            GenerateTestArchives.main(new String[]{
                    "--out", staging.toString(), "--count", "1", "--start-size", "8MB",
                    "--threads", "1", "--seed", "999"});
            Files.move(staging.resolve("archive-v01.zip"),
                    store.archiveDir().resolve("archive-v02.zip"));

            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + api.adminPort() + "/api/rescan"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            assertTrue(body.contains("\"ingested\" : 1"), "unexpected response: " + body);
            assertEquals(2, store.versions().size());
        } finally {
            api.close();
            store.close();
        }
    }
}
