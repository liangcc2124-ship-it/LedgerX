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
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogHttpServerTest {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String CATEGORY = "c5b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String TARGET_CATEGORY = "c6b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String ARCHIVE_CATEGORY = "c7b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String ACCOUNT = "f5a0c2ec-6b64-48c7-9f7f-0b604e4d8901";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private LedgerHttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(Duration.ofSeconds(2)); }

    @Test
    void catalogHttpLifecycleUsesRealApplicationAndOptimisticHeaders(@TempDir Path temp) throws Exception {
        Path web = temp.resolve("web"); Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        ProfileApplicationService service = ProfileApplicationService.open(temp.resolve("data"), "test", CLOCK);
        server = new LedgerHttpServer(TOKEN, new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.fromDirectory(web), service, service, service, service, service, service,
                new PrintStream(new ByteArrayOutputStream()), 2, 2);
        server.start();

        HttpResponse<String> initial = send("GET", "/api/v1/categories?limit=1", null, null, null);
        assertEquals(200, initial.statusCode());
        assertTrue(initial.headers().firstValue("etag").orElse("").matches("\\\"\\d+\\\""));

        String categoryBody = "{\"id\":\"" + CATEGORY + "\",\"name\":\"HTTP分类\",\"parentId\":null}";
        HttpResponse<String> createdCategory = send("POST", "/api/v1/categories", categoryBody, "00000000-0000-4000-8000-000000000101", null);
        assertEquals(201, createdCategory.statusCode());
        assertEquals("HTTP分类", JSON.readTree(createdCategory.body()).at("/data/category/name").asText());
        String categoryRevision = JSON.readTree(createdCategory.body()).at("/data/category/revision").asText();
        HttpResponse<String> updatedCategory = send("PUT", "/api/v1/categories/" + CATEGORY,
                "{\"name\":\"HTTP分类改名\",\"parentId\":null}", "00000000-0000-4000-8000-000000000102", categoryRevision);
        assertEquals(200, updatedCategory.statusCode());
        assertEquals("HTTP分类改名", JSON.readTree(updatedCategory.body()).at("/data/category/name").asText());
        HttpResponse<String> targetCategory = send("POST", "/api/v1/categories",
                "{\"id\":\"" + TARGET_CATEGORY + "\",\"name\":\"HTTP目标分类\",\"parentId\":null}",
                "00000000-0000-4000-8000-000000000109", null);
        assertEquals(201, targetCategory.statusCode());
        HttpResponse<String> merged = send("POST", "/api/v1/categories/" + TARGET_CATEGORY + "/merge",
                "{\"sources\":[{\"id\":\"" + CATEGORY + "\",\"expectedRevision\":1}]}",
                "00000000-0000-4000-8000-000000000110", "0");
        assertEquals(200, merged.statusCode());
        assertEquals(TARGET_CATEGORY, JSON.readTree(merged.body()).at("/data/targetId").asText());

        HttpResponse<String> archiveCandidate = send("POST", "/api/v1/categories",
                "{\"id\":\"" + ARCHIVE_CATEGORY + "\",\"name\":\"HTTP归档分类\",\"parentId\":null}",
                "00000000-0000-4000-8000-000000000111", null);
        assertEquals(201, archiveCandidate.statusCode());

        HttpResponse<String> createdAccount = send("POST", "/api/v1/accounts",
                "{\"id\":\"" + ACCOUNT + "\",\"name\":\"HTTP银行\",\"kind\":\"BANK\",\"openingOn\":\"2026-01-01\",\"openingBalance\":\"10.00\",\"currency\":\"CNY\",\"includeInAvailableCash\":true}",
                "00000000-0000-4000-8000-000000000103", null);
        assertEquals(201, createdAccount.statusCode());
        assertEquals("10.00", JSON.readTree(createdAccount.body()).at("/data/account/balance").asText());
        String accountRevision = JSON.readTree(createdAccount.body()).at("/data/account/revision").asText();
        HttpResponse<String> updatedAccount = send("PUT", "/api/v1/accounts/" + ACCOUNT,
                "{\"name\":\"HTTP银行改名\",\"kind\":\"BANK\",\"openingOn\":\"2026-01-01\",\"openingBalance\":\"12.00\",\"currency\":\"CNY\",\"includeInAvailableCash\":true}",
                "00000000-0000-4000-8000-000000000104", accountRevision);
        assertEquals(200, updatedAccount.statusCode());
        assertEquals("12.00", JSON.readTree(updatedAccount.body()).at("/data/account/balance").asText());

        assertEquals(409, send("DELETE", "/api/v1/accounts/" + ACCOUNT, null,
                "00000000-0000-4000-8000-000000000105", "0").statusCode());
        assertEquals(200, send("DELETE", "/api/v1/accounts/" + ACCOUNT, null,
                "00000000-0000-4000-8000-000000000106", "1").statusCode());
        JsonNode accounts = JSON.readTree(send("GET", "/api/v1/accounts?includeArchived=true", null, null, null).body()).at("/data/items");
        assertTrue(accounts.toString().contains(ACCOUNT));
        assertEquals(409, send("DELETE", "/api/v1/accounts/" + "f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901", null,
                "00000000-0000-4000-8000-000000000107", "0").statusCode());

        HttpResponse<String> archivedCategory = send("DELETE", "/api/v1/categories/" + ARCHIVE_CATEGORY, null,
                "00000000-0000-4000-8000-000000000112", "0");
        assertEquals(200, archivedCategory.statusCode());
        assertEquals("ARCHIVED", JSON.readTree(archivedCategory.body()).at("/data/category/status").asText());
        assertEquals(200, send("GET", "/api/v1/categories?includeArchived=true", null, null, null).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String expectedRevision) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.origin() + path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Request-Id", "00000000-0000-4000-8000-000000000201")
                .header("Origin", server.origin());
        if (key != null) builder.header("Idempotency-Key", key);
        if (expectedRevision != null) builder.header("If-Match", "\"" + expectedRevision + "\"");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
