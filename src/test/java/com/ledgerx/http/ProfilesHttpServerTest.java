package com.ledgerx.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.bootstrap.ApplicationRuntime;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilesHttpServerTest {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRST_ID = "17b65036-e5b1-4de9-b78d-068ae54ab047";
    private static final String SECOND_ID = "a1165036-e5b1-4de9-b78d-068ae54ab047";

    @Test
    void realHttpProfilesContractCoversLifecycleConcurrencyReplayAndGuards(@TempDir Path temp) throws Exception {
        ApplicationRuntime runtime = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        String originalId = runtime.current().getActiveProfileId();
        LedgerHttpServer server = server(temp, runtime);
        HttpClient client = HttpClient.newHttpClient();
        try {
            server.start();
            HttpResponse<String> initialList = client.send(api(server, "/api/v1/profiles")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initialList.statusCode());
            assertEquals(originalId, JSON.readTree(initialList.body()).at("/data/activeProfileId").asText());

            String firstKey = "2f5aedce-f6cb-4b8e-bfac-5ca95808e060";
            HttpResponse<String> create = client.send(mutation(server, "/api/v1/profiles", firstKey,
                    "{\"id\":\"" + FIRST_ID + "\",\"name\":\"  家庭账本  \",\"ignored\":true}")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + FIRST_ID
                            + "\",\"name\":\"  家庭账本  \",\"ignored\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, create.statusCode());
            assertEquals("\"0\"", create.headers().firstValue("ETag").orElse(null));
            assertEquals("/api/v1/profiles/" + FIRST_ID, create.headers().firstValue("Location").orElse(null));
            assertEquals("家庭账本", JSON.readTree(create.body()).at("/data/profile/name").asText());
            JsonNode createFixture = JSON.readTree(Files.readString(
                    Path.of("docs/contracts/api-v1/profiles/create-profile-success.json"), StandardCharsets.UTF_8));
            assertEquals(createFixture.at("/data/profile/id").asText(),
                    JSON.readTree(create.body()).at("/data/profile/id").asText());
            assertEquals(createFixture.at("/data/profile/status").asText(),
                    JSON.readTree(create.body()).at("/data/profile/status").asText());
            assertEquals(createFixture.at("/meta/catalogRevision").asLong(),
                    JSON.readTree(create.body()).at("/meta/catalogRevision").asLong());

            HttpResponse<String> replay = client.send(mutation(server, "/api/v1/profiles", firstKey,
                    "{\"id\":\"" + FIRST_ID + "\",\"name\":\"  家庭账本  \",\"ignored\":true}")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + FIRST_ID
                            + "\",\"name\":\"  家庭账本  \",\"ignored\":true}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, replay.statusCode());
            assertEquals(create.body(), replay.body());
            assertEquals(create.headers().firstValue("ETag"), replay.headers().firstValue("ETag"));

            HttpResponse<String> changedReplay = client.send(mutation(server, "/api/v1/profiles", firstKey,
                    "{\"id\":\"" + FIRST_ID + "\",\"name\":\"另一名称\"}")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + FIRST_ID
                            + "\",\"name\":\"另一名称\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, changedReplay.statusCode());
            assertEquals("IDEMPOTENCY_CONFLICT", errorCode(changedReplay));

            HttpResponse<String> operation = client.send(api(server, "/api/v1/operations/" + firstKey)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, operation.statusCode());
            assertEquals(201, JSON.readTree(operation.body()).at("/data/responseStatus").asInt());
            assertEquals(FIRST_ID, JSON.readTree(operation.body()).at("/data/result/profile/id").asText());
            JsonNode operationFixture = JSON.readTree(Files.readString(
                    Path.of("docs/contracts/api-v1/profiles/operation-completed.json"), StandardCharsets.UTF_8));
            assertEquals(operationFixture.at("/data/idempotencyKey").asText(),
                    JSON.readTree(operation.body()).at("/data/idempotencyKey").asText());
            assertEquals(operationFixture.at("/data/status").asText(),
                    JSON.readTree(operation.body()).at("/data/status").asText());

            HttpResponse<String> onePage = client.send(api(server, "/api/v1/profiles?limit=1")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            String expiredCursor = JSON.readTree(onePage.body()).at("/data/page/nextCursor").asText();
            assertFalse(expiredCursor.isEmpty());

            HttpResponse<String> createSecond = client.send(mutation(server, "/api/v1/profiles",
                    "5e0e933a-08c6-4392-a85d-07e71dca738e", "{\"id\":\"" + SECOND_ID + "\",\"name\":\"工作\"}")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + SECOND_ID
                            + "\",\"name\":\"工作\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, createSecond.statusCode());

            HttpResponse<String> staleCursor = client.send(api(server, "/api/v1/profiles?limit=1&cursor=" + expiredCursor)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, staleCursor.statusCode());
            assertEquals("VALIDATION_FAILED", errorCode(staleCursor));

            HttpResponse<String> missingIfMatch = client.send(mutation(server,
                    "/api/v1/profiles/" + FIRST_ID + "/activate", "1067c4a7-4e5c-4b5f-9de3-006a49b04480", "{}")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(428, missingIfMatch.statusCode());

            HttpResponse<String> activeArchive = client.send(mutation(server, "/api/v1/profiles/" + SECOND_ID,
                    "f6301d6d-6c0b-4ccd-90dd-1fbb3d64d071", "")
                    .header("If-Match", "\"0\"")
                    .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, activeArchive.statusCode());
            assertEquals("不能归档当前用户空间。", JSON.readTree(activeArchive.body()).at("/error/fieldErrors/id").asText());

            HttpResponse<String> status = client.send(api(server, "/api/v1/system/status")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(SECOND_ID, JSON.readTree(status.body()).at("/data/activeProfileId").asText());
            assertEquals(0, JSON.readTree(status.body()).at("/meta/dataRevision").asInt());
            assertEquals("profiles.read", JSON.readTree(status.body()).at("/data/capabilities/1").asText());
            assertEquals("profiles.write", JSON.readTree(status.body()).at("/data/capabilities/2").asText());

            HttpResponse<String> invalidName = client.send(mutation(server, "/api/v1/profiles",
                    "a6522f54-1f0a-4860-9e1f-bd0f37bd1f4d", "{\"id\":\"b1165036-e5b1-4de9-b78d-068ae54ab047\",\"name\":\"   \"}")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"b1165036-e5b1-4de9-b78d-068ae54ab047\",\"name\":\"   \"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidName.statusCode());
            assertEquals("VALIDATION_FAILED", errorCode(invalidName));

            HttpResponse<String> wrongOrigin = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(server.origin() + "/api/v1/profiles"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("Idempotency-Key", "8a22c53f-4874-4b1e-847d-3cb6f28d9d0c")
                    .header("Origin", "http://example.invalid")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"c1165036-e5b1-4de9-b78d-068ae54ab047\",\"name\":\"拒绝\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, wrongOrigin.statusCode());

            HttpResponse<String> wrongToken = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(server.origin() + "/api/v1/profiles"))
                    .header("Authorization", "Bearer wrong")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrongToken.statusCode());

            HttpResponse<String> finalList = client.send(api(server, "/api/v1/profiles")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(3, JSON.readTree(finalList.body()).at("/data/items").size());
            assertFalse(finalList.body().contains("拒绝"));
        } finally {
            server.stop(java.time.Duration.ofSeconds(2));
        }
    }

    private static LedgerHttpServer server(Path temp, ApplicationRuntime runtime) throws Exception {
        Path web = temp.resolve("web");
        Files.createDirectories(web.resolve("assets"));
        Files.writeString(web.resolve("index.html"), "<h1>test</h1>", StandardCharsets.UTF_8);
        return new LedgerHttpServer(TOKEN, new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.forDirectory(web, Collections.singletonMap("index.html", "index.html")),
                runtime, runtime, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), 2, 8);
    }

    private static HttpRequest.Builder api(LedgerHttpServer server, String path) {
        return HttpRequest.newBuilder().uri(URI.create(server.origin() + path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Request-Id", UUID.randomUUID().toString());
    }

    private static HttpRequest.Builder mutation(LedgerHttpServer server, String path, String idempotencyKey,
            String ignored) {
        return api(server, path).header("Idempotency-Key", idempotencyKey)
                .header("Origin", server.origin()).header("Content-Type", "application/json");
    }

    private static String errorCode(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).at("/error/code").asText();
    }
}
