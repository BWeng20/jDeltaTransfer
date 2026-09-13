package com.bw.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bw.jdt.core.Chunker;
import com.bw.jdt.server.HttpApi;
import com.bw.jdt.server.VersionStore;
import com.bw.jdt.tools.GenerateTestArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the documented JSON schemas in {@code docs/schema} against what the server actually
 * sends, so the API reference cannot drift away from the implementation unnoticed.
 *
 * <p>Deliberately a small validator rather than a schema library: it covers exactly the keywords
 * these documents use ({@code type}, {@code required}, {@code additionalProperties:false},
 * {@code const}, {@code pattern}, {@code minimum}, {@code items}, {@code $ref}) and keeps the
 * build free of another dependency.
 */
class ApiContractTest {

    private static final Path SCHEMA_DIR = Path.of("docs", "schema");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void apiResponsesAndTheStoreIndexMatchTheDocumentedSchemas(@TempDir Path tmp) throws Exception {
        Path archives = tmp.resolve("archives");
        GenerateTestArchives.main(new String[]{
                "--out", archives.toString(), "--count", "2", "--start-size", "8MB", "--threads", "2"});

        Chunker.Params params = Chunker.Params.of(32768, 1 << 20);
        Path storeDir = tmp.resolve("store");
        VersionStore store = VersionStore.open(storeDir, archives, params, true, false);
        store.scan(VersionStore.Log.STDOUT);

        HttpApi api = new HttpApi(store,
                new HttpApi.Endpoints("127.0.0.1", 0, "127.0.0.1", 0),
                1 << 20, 4, com.bw.jdt.proto.Wire.DEFAULT_COMPRESSION_LEVEL,
                VersionStore.Log.STDOUT);
        api.start();
        try (HttpClient http = HttpClient.newHttpClient()) {
            URI base = URI.create("http://127.0.0.1:" + api.port());
            URI admin = URI.create("http://127.0.0.1:" + api.adminPort());

            validate("version-list.schema.json", getJson(http, base.resolve("/api/versions")));
            validate("version.schema.json", getJson(http, base.resolve("/api/versions/archive-v01")));

            // The same path answers differently per port, so each shape gets its own schema.
            // additionalProperties:false in both means a field leaking from one into the other
            // fails here rather than in production.
            validate("client-config.schema.json", getJson(http, base.resolve("/api/config")));
            validate("config.schema.json", getJson(http, admin.resolve("/api/config")));

            // Rescan lives on the admin port only; the transfer port does not carry it.
            HttpResponse<String> rescan = http.send(
                    HttpRequest.newBuilder(admin.resolve("/api/rescan")).POST(
                            HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rescan.statusCode());
            validate("rescan.schema.json", MAPPER.readTree(rescan.body()));

            // The documented error behaviour: plain text, not JSON.
            HttpResponse<String> missing = http.send(
                    HttpRequest.newBuilder(base.resolve("/api/versions/does-not-exist")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            assertTrue(missing.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),
                    "errors are documented as text/plain, got "
                            + missing.headers().firstValue("Content-Type").orElse("(none)"));
        } finally {
            api.close();
            store.close();
        }

        validate("store-index.schema.json",
                MAPPER.readTree(Files.readAllBytes(storeDir.resolve("index.json"))));
    }

    private static JsonNode getJson(HttpClient http, URI uri) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), uri + " returned HTTP " + r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                uri + " must be JSON");
        return MAPPER.readTree(r.body());
    }

    private static void validate(String schemaFile, JsonNode instance) throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readAllBytes(SCHEMA_DIR.resolve(schemaFile)));
        List<String> errors = new ArrayList<>();
        check(schema, instance, schemaFile, "$", errors);
        if (!errors.isEmpty()) {
            fail("instance does not match " + schemaFile + ":\n  " + String.join("\n  ", errors));
        }
    }

    private static void check(JsonNode schema, JsonNode node, String schemaFile, String path,
                              List<String> errors) throws IOException {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            JsonNode target = MAPPER.readTree(Files.readAllBytes(SCHEMA_DIR.resolve(ref)));
            check(target, node, ref, path, errors);
            return;
        }

        String type = schema.path("type").asText("");
        switch (type) {
            case "object" -> {
                if (!node.isObject()) {
                    errors.add(path + ": expected object, got " + node.getNodeType());
                    return;
                }
                for (JsonNode req : schema.path("required")) {
                    if (!node.has(req.asText())) {
                        errors.add(path + ": missing required property '" + req.asText() + "'");
                    }
                }
                JsonNode props = schema.path("properties");
                if (!schema.path("additionalProperties").asBoolean(true)) {
                    Iterator<String> it = node.fieldNames();
                    while (it.hasNext()) {
                        String name = it.next();
                        if (!props.has(name)) {
                            errors.add(path + ": undocumented property '" + name
                                    + "' (add it to " + schemaFile + " or remove it from the response)");
                        }
                    }
                }
                Iterator<String> declared = props.fieldNames();
                while (declared.hasNext()) {
                    String name = declared.next();
                    if (node.has(name)) {
                        check(props.get(name), node.get(name), schemaFile, path + "." + name, errors);
                    }
                }
            }
            case "array" -> {
                if (!node.isArray()) {
                    errors.add(path + ": expected array, got " + node.getNodeType());
                    return;
                }
                JsonNode items = schema.path("items");
                if (!items.isMissingNode()) {
                    for (int i = 0; i < node.size(); i++) {
                        check(items, node.get(i), schemaFile, path + "[" + i + "]", errors);
                    }
                }
            }
            case "string" -> {
                if (!node.isTextual()) {
                    errors.add(path + ": expected string, got " + node.getNodeType());
                    return;
                }
                if (schema.has("pattern")
                        && !Pattern.compile(schema.get("pattern").asText()).matcher(node.asText()).find()) {
                    errors.add(path + ": '" + node.asText() + "' violates pattern "
                            + schema.get("pattern").asText());
                }
                if (schema.has("minLength") && node.asText().length() < schema.get("minLength").asInt()) {
                    errors.add(path + ": shorter than minLength");
                }
            }
            case "integer" -> {
                if (!node.isIntegralNumber()) {
                    errors.add(path + ": expected integer, got " + node.getNodeType());
                    return;
                }
                if (schema.has("minimum") && node.asLong() < schema.get("minimum").asLong()) {
                    errors.add(path + ": " + node.asLong() + " below minimum "
                            + schema.get("minimum").asLong());
                }
            }
            default -> {
                // No type keyword: nothing structural to check beyond const below.
            }
        }

        if (schema.has("const") && !schema.get("const").equals(node)) {
            errors.add(path + ": expected const " + schema.get("const") + ", got " + node);
        }
    }
}
