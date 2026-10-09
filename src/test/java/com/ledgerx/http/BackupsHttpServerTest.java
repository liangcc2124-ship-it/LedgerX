package com.ledgerx.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.profile.ProfileApplicationService;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.ledgerx.testsupport.LedgerTestSupport.openReadyLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupsHttpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private LedgerHttpServer server;
    private BrowserTestSession session;

    @AfterEach
    void stop() { if (server != null) server.stop(Duration.ofSeconds(2)); }

    @Test
    void browserSessionCreatesListsVerifiesAndDownloads(@TempDir Path temp) throws Exception {
        Path web = temp.resolve("web");
        Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        ProfileApplicationService service = openReadyLedger(temp.resolve("data"), "test", Clock.fixed(
                Instant.parse("2026-09-24T04:30:00Z"), ZoneOffset.UTC));
        server = new LedgerHttpServer(new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.fromDirectory(web), service, service, service, service, service, service,
                new PrintStream(new ByteArrayOutputStream()), 2, 4);
        server.start();
        session = new BrowserTestSession(server.origin());
        String id = "9fc1df09-f1d3-4c9b-97be-f592f65d0c29";

        HttpResponse<String> created = json("POST", "/api/v1/backups", "{}", id);
        assertEquals(201, created.statusCode());
        assertEquals(id, JSON.readTree(created.body()).at("/data/backup/id").asText());
        assertFalseEncrypted(JSON.readTree(created.body()));
        assertEquals(created.body(), json("POST", "/api/v1/backups", "{}", id).body());

        HttpResponse<String> listed = json("GET", "/api/v1/backups?limit=25", null, null);
        assertEquals(200, listed.statusCode());
        assertEquals(1, JSON.readTree(listed.body()).at("/data/items").size());
        assertEquals(200, json("GET", "/api/v1/backups/" + id + "/verify", null, null).statusCode());
        assertEquals(404, json("GET", "/api/v1/backups/not-an-id/verify", null, null).statusCode());

        HttpRequest.Builder downloadRequest = request("GET", "/api/v1/backups/" + id + "/download");
        session.apply(downloadRequest);
        HttpResponse<byte[]> download = CLIENT.send(downloadRequest.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals("application/vnd.ledgerx.backup+zip", download.headers().firstValue("content-type").orElse(""));
        assertTrue(download.body().length > 0);

        HttpRequest unauthenticated = request("GET", "/api/v1/backups").GET().build();
        assertEquals(401, CLIENT.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private HttpResponse<String> json(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder builder = request(method, path);
        session.apply(builder);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path) {
        return HttpRequest.newBuilder(URI.create(server.origin() + path)).timeout(Duration.ofSeconds(10))
                .header("X-Request-Id", java.util.UUID.randomUUID().toString());
    }

    private static void assertFalseEncrypted(JsonNode root) {
        assertEquals(false, root.at("/data/backup/encrypted").asBoolean(true));
    }
}
