package com.ledgerx.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.ledger.CategoryMutation;
import com.ledgerx.application.ledger.CategoryPatch;
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
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordsHttpServerTest {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String ACCOUNT = "f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901";
    private static final String CATEGORY = "c4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String RECORD = "d4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String RECORD_TWO = "e4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private LedgerHttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(Duration.ofSeconds(2)); }

    @Test
    void recordsHttpLifecycleUsesRealApplicationAndHeaders(@TempDir Path temp) throws Exception {
        Path web = temp.resolve("web");
        Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        ProfileApplicationService service = ProfileApplicationService.open(temp.resolve("data"), "test", CLOCK);
        service.createCategory(new CategoryPatch(CATEGORY, "HTTP测试", null),
                new CategoryMutation("20000000-0000-4000-8000-000000000001", "POST", "/api/v1/categories", "a".repeat(64)));
        server = new LedgerHttpServer(TOKEN, new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.fromDirectory(web), service, service, service, service, service, service,
                new PrintStream(new ByteArrayOutputStream()), 2, 2);
        server.start();

        HttpResponse<String> empty = send("GET", "/api/v1/records?status=ACTIVE&limit=50", null, null, "0");
        assertEquals(200, empty.statusCode());
        assertEquals(0, JSON.readTree(empty.body()).at("/data/items").size());

        String body = "{\"id\":\"" + RECORD + "\",\"occurredOn\":\"2026-09-10\",\"recordType\":\"INCOME\","
                + "\"amount\":\"12.30\",\"currency\":\"CNY\",\"categoryId\":\"" + CATEGORY + "\","
                + "\"settlement\":{\"mode\":\"PAID_FROM_ACCOUNT\",\"accountId\":\"" + ACCOUNT
                + "\",\"settlementOn\":\"2026-09-14\"},\"note\":\"HTTP\"}";
        HttpResponse<String> created = send("POST", "/api/v1/records", body, "key-1", "0");
        assertEquals(201, created.statusCode());
        assertEquals("\"0\"", created.headers().firstValue("etag").orElse(""));
        assertEquals("12.30", JSON.readTree(created.body()).at("/data/record/amount").asText());
        String operationKey = "30000000-0000-4000-8000-100000000000";
        HttpResponse<String> operation = send("GET", "/api/v1/operations/" + operationKey, null, null, "0");
        assertEquals(200, operation.statusCode());
        assertEquals("COMPLETED", JSON.readTree(operation.body()).at("/data/status").asText());

        String secondBody = body.replace(RECORD, RECORD_TWO).replace("INCOME", "FIXED_COST")
                .replace("12.30", "5.00").replace("HTTP", "订阅");
        assertEquals(201, send("POST", "/api/v1/records", secondBody, "key-8", "1").statusCode());
        HttpResponse<String> filtered = send("GET", "/api/v1/records?occurredFrom=2026-09-01&occurredToExclusive=2026-09-20"
                + "&recordType=FIXED_COST&categoryId=" + CATEGORY + "&accountId=" + ACCOUNT
                + "&query=%E8%AE%A2%E9%98%85&amountMin=5.00&amountMax=5.00&limit=50", null, null, "0");
        assertEquals(200, filtered.statusCode());
        assertEquals(1, JSON.readTree(filtered.body()).at("/data/items").size());
        assertEquals(RECORD_TWO, JSON.readTree(filtered.body()).at("/data/items/0/id").asText());

        HttpResponse<String> firstPage = send("GET", "/api/v1/records?status=ACTIVE&limit=1", null, null, "0");
        String nextCursor = JSON.readTree(firstPage.body()).at("/data/page/nextCursor").asText();
        assertTrue(!nextCursor.isEmpty());
        HttpResponse<String> secondPage = send("GET", "/api/v1/records?status=ACTIVE&limit=1&cursor=" + nextCursor, null, null, "0");
        assertEquals(200, secondPage.statusCode());
        assertEquals(400, send("GET", "/api/v1/records?recordType=INCOME&recordType=INCOME", null, null, "0").statusCode());
        assertEquals(400, send("GET", "/api/v1/records?status=ACTIVE&limit=2&cursor=" + nextCursor, null, null, "0").statusCode());

        HttpResponse<String> badField = send("POST", "/api/v1/records", body.replace("\"note\":\"HTTP\"", "\"asset\":true,\"note\":\"HTTP\""), "key-2", "1");
        assertEquals(400, badField.statusCode());
        assertEquals(400, send("POST", "/api/v1/records", body.replace("\"12.30\"", "\"0.00\""), "key-5", "1").statusCode());
        assertEquals(400, send("POST", "/api/v1/records", body.replace("\"12.30\"", "\"00.10\""), "key-6", "1").statusCode());
        assertEquals(400, send("POST", "/api/v1/records", body.replace("\"12.30\"", "\"1.234\""), "key-7", "1").statusCode());

        HttpResponse<String> trashed = send("DELETE", "/api/v1/records/" + RECORD, null, "key-3", "2");
        assertEquals(200, trashed.statusCode());
        assertEquals("TRASHED", JSON.readTree(trashed.body()).at("/data/record/status").asText());
        assertEquals(404, send("GET", "/api/v1/records/" + RECORD + "?status=ACTIVE", null, null, "0").statusCode());
        assertEquals("TRASHED", JSON.readTree(send("GET", "/api/v1/records/" + RECORD + "?status=TRASHED", null, null, "0").body()).at("/data/record/status").asText());
        HttpResponse<String> restored = send("POST", "/api/v1/records/" + RECORD + "/restore", "{}", "key-4", "3");
        assertEquals(200, restored.statusCode());
        assertEquals("ACTIVE", JSON.readTree(restored.body()).at("/data/record/status").asText());
        HttpResponse<String> replay = send("POST", "/api/v1/records", body, "key-1", "4");
        assertEquals(created.statusCode(), replay.statusCode());
        assertEquals(created.body(), replay.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String suffix) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.origin() + path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Request-Id", "00000000-0000-4000-8000-" + suffix + "00000000000")
                .header("Origin", server.origin());
        if (key != null) builder.header("Idempotency-Key", "30000000-0000-4000-8000-" + key.substring(key.length()-1) + "00000000000");
        if (suffix != null && !"0".equals(suffix)) builder.header("If-Match", "\"" + ("2".equals(suffix) ? "0" : "1") + "\"");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
