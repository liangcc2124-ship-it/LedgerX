package com.ledgerx.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.bootstrap.ApplicationRuntime;
import com.ledgerx.application.settings.SettingsApi;
import com.ledgerx.application.settings.SettingsApiResult;
import com.ledgerx.application.settings.SettingsException;
import com.ledgerx.application.settings.SettingsMutation;
import com.ledgerx.application.settings.SettingsPatch;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsHttpServerTest {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String WRONG_TOKEN = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T04:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPDATE_KEY = "94a69e14-99a1-4b63-9548-8e439430e23d";
    private static final String UPDATE_BODY = "{\"notificationsEnabled\":true,\"safetyBuffer\":{\"amount\":\"3000.01\","
            + "\"currency\":\"CNY\"},\"hideAllAmounts\":true,\"themeName\":\"GRAPHITE\",\"ignored\":true}";

    @Test
    void realHttpSettingsContractCoversValidationReplayAndRestart(@TempDir Path temp) throws Exception {
        ApplicationRuntime runtime = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        HttpClient client = HttpClient.newHttpClient();
        LedgerHttpServer server = server(temp, runtime);
        String firstResponse;
        try {
            server.start();
            HttpResponse<String> initial = client.send(api(server, "/api/v1/settings").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initial.statusCode());
            assertEquals("\"0\"", initial.headers().firstValue("ETag").orElse(null));
            assertJsonFixture("docs/contracts/api-v1/settings/get-default.json", initial.body());

            HttpResponse<String> wrongToken = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(server.origin() + "/api/v1/settings"))
                    .header("Authorization", "Bearer " + WRONG_TOKEN)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("Origin", server.origin())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", "\"0\"")
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(UPDATE_BODY))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrongToken.statusCode());

            HttpResponse<String> wrongOrigin = client.send(patch(server, UPDATE_KEY, "\"0\"", UPDATE_BODY)
                    .header("Origin", "http://example.invalid").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, wrongOrigin.statusCode());

            HttpResponse<String> update = client.send(patch(server, UPDATE_KEY, "\"0\"", UPDATE_BODY)
                    .header("Origin", server.origin()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, update.statusCode());
            assertEquals("\"1\"", update.headers().firstValue("ETag").orElse(null));
            assertJsonFixture("docs/contracts/api-v1/settings/patch-settings-success.json", update.body());
            firstResponse = update.body();

            HttpResponse<String> readonly = client.send(patch(server, UUID.randomUUID().toString(), "\"1\"",
                    "{\"currency\":{\"code\":\"CNY\",\"symbol\":\"¥\"}}")
                    .header("Origin", server.origin()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, readonly.statusCode());
            assertEquals("VALIDATION_FAILED", JSON.readTree(readonly.body()).at("/error/code").asText());

            HttpResponse<String> invalidMoney = client.send(patch(server, UUID.randomUUID().toString(), "\"1\"",
                    "{\"safetyBuffer\":{\"amount\":\"1.001\",\"currency\":\"CNY\"}}")
                    .header("Origin", server.origin()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidMoney.statusCode());
            assertEquals("safetyBuffer.amount", JSON.readTree(invalidMoney.body()).at("/error/fieldErrors").fieldNames().next());

            HttpResponse<String> missingMatch = client.send(patch(server, UUID.randomUUID().toString(), null,
                    "{\"hideAllAmounts\":true}").header("Origin", server.origin()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(428, missingMatch.statusCode());

            HttpResponse<String> stale = client.send(patch(server, UUID.randomUUID().toString(), "\"0\"",
                    "{\"hideAllAmounts\":false}").header("Origin", server.origin()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, stale.statusCode());
            assertEquals(1L, JSON.readTree(stale.body()).at("/error/details/currentRevision").asLong());

            HttpResponse<String> wrongReuse = client.send(patch(server, UPDATE_KEY, "\"1\"",
                    "{\"hideAllAmounts\":true}").header("Origin", server.origin()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, wrongReuse.statusCode());
        } finally {
            server.stop(Duration.ofSeconds(2));
        }

        ApplicationRuntime reopened = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        LedgerHttpServer restarted = server(temp, reopened);
        try {
            restarted.start();
            HttpResponse<String> replay = client.send(patch(restarted, UPDATE_KEY, "\"0\"", UPDATE_BODY)
                    .header("Origin", restarted.origin()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, replay.statusCode());
            assertEquals(firstResponse, replay.body());
            assertEquals("\"1\"", replay.headers().firstValue("ETag").orElse(null));

            HttpResponse<String> noOp = client.send(patch(restarted, "4ea58670-2cd9-41e6-9d35-2e4a00cda41b", "\"1\"",
                    "{\"hideAllAmounts\":true}").header("Origin", restarted.origin()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, noOp.statusCode());
            assertEquals("\"1\"", noOp.headers().firstValue("ETag").orElse(null));
            assertEquals(1L, JSON.readTree(noOp.body()).at("/meta/dataRevision").asLong());

            try (Connection connection = new com.ledgerx.persistence.SqliteDatabase(
                    temp.resolve("Profiles").resolve(reopened.current().getActiveProfileId()).resolve("ledger.db")).open();
                    Statement statement = connection.createStatement();
                    ResultSet operations = statement.executeQuery("SELECT COUNT(*) FROM processed_operation")) {
                assertTrue(operations.next());
                assertEquals(2, operations.getInt(1));
            }
        } finally {
            restarted.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void authenticationAndOriginGuardsDoNotCallTheSettingsPort() throws Exception {
        AtomicInteger settingsCalls = new AtomicInteger();
        SettingsApi settings = new SettingsApi() {
            @Override
            public SettingsApiResult read() throws SettingsException {
                settingsCalls.incrementAndGet();
                throw new AssertionError("settings read must not run");
            }

            @Override
            public SettingsApiResult update(long expectedRevision, SettingsPatch patch, SettingsMutation mutation)
                    throws SettingsException {
                settingsCalls.incrementAndGet();
                throw new AssertionError("settings update must not run");
            }
        };
        SystemStatusProvider status = () -> new SystemStatus("1.0", "test", null, 3, null,
                "RECOVERY_REQUIRED", Collections.singletonList("system.status"), null);
        LedgerHttpServer server = new LedgerHttpServer(TOKEN, new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.fromClasspath(), status, null, settings,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), 1, 1);
        HttpClient client = HttpClient.newHttpClient();
        try {
            server.start();
            HttpResponse<String> wrongToken = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(server.origin() + "/api/v1/settings"))
                    .header("Authorization", "Bearer " + WRONG_TOKEN)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("Origin", server.origin())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", "\"0\"")
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"hideAllAmounts\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrongToken.statusCode());

            HttpResponse<String> wrongOrigin = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(server.origin() + "/api/v1/settings"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("Origin", "http://example.invalid")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", "\"0\"")
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"hideAllAmounts\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, wrongOrigin.statusCode());
            assertEquals(0, settingsCalls.get());
        } finally {
            server.stop(Duration.ofSeconds(2));
        }
    }

    private static LedgerHttpServer server(Path temp, ApplicationRuntime runtime) throws Exception {
        Path web = temp.resolve("web");
        java.nio.file.Files.createDirectories(web.resolve("assets"));
        java.nio.file.Files.writeString(web.resolve("index.html"), "<h1>test</h1>", StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(web.resolve("assets/app.js"), "console.log('test');", StandardCharsets.UTF_8);
        return new LedgerHttpServer(TOKEN, new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.forDirectory(web, Collections.singletonMap("index.html", "index.html")), runtime,
                runtime, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), 2, 8);
    }

    private static HttpRequest.Builder api(LedgerHttpServer server, String path) {
        return HttpRequest.newBuilder().uri(URI.create(server.origin() + path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Request-Id", UUID.randomUUID().toString());
    }

    private static HttpRequest.Builder patch(LedgerHttpServer server, String key, String ifMatch, String body) {
        HttpRequest.Builder builder = api(server, "/api/v1/settings")
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json");
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
    }

    private static void assertJsonFixture(String file, String actual) throws Exception {
        assertEquals(JSON.readTree(java.nio.file.Files.readString(Path.of(file), StandardCharsets.UTF_8)), JSON.readTree(actual));
    }
}
