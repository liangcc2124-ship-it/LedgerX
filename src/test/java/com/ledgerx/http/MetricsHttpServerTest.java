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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.ledgerx.testsupport.LedgerTestSupport.openReadyLedger;

class MetricsHttpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private LedgerHttpServer server;
    private BrowserTestSession session;

    @AfterEach void stop() { if (server != null) server.stop(Duration.ofSeconds(2)); }

    @Test
    void exposesMetricDashboardAndLayoutRoutes(@TempDir Path temp) throws Exception {
        Path web = temp.resolve("web"); Files.createDirectories(web); Files.writeString(web.resolve("index.html"), "ok", StandardCharsets.UTF_8);
        ProfileApplicationService service = openReadyLedger(temp.resolve("data"), "test", CLOCK);
        server = new LedgerHttpServer(new InetSocketAddress("127.0.0.1", 0), StaticResourceManifest.fromDirectory(web),
                service, service, service, service, service, service, new PrintStream(new ByteArrayOutputStream()), 2, 2);
        server.start();
        HttpResponse<String> metrics = send("GET", "/api/v1/metrics", null, null, null);
        assertEquals(200, metrics.statusCode()); assertEquals(17, JSON.readTree(metrics.body()).at("/data/items").size());
        HttpResponse<String> paged = send("GET", "/api/v1/metrics?limit=1&dashboardEnabled=true", null, null, null);
        assertEquals(200, paged.statusCode());
        assertEquals(1, JSON.readTree(paged.body()).at("/data/items").size());
        assertTrue(JSON.readTree(paged.body()).at("/data/page/hasMore").asBoolean());
        HttpResponse<String> archivedList = send("GET", "/api/v1/metrics?status=ARCHIVED", null, null, null);
        assertEquals(0, JSON.readTree(archivedList.body()).at("/data/items").size());
        HttpResponse<String> income = send("GET", "/api/v1/metrics/income", null, null, null);
        assertEquals(200, income.statusCode());
        String incomeRevision = JSON.readTree(income.body()).at("/data/metric/revision").asText();
        HttpResponse<String> visibility = send("PUT", "/api/v1/metrics/income",
                "{\"visibility\":{\"hidden\":true,\"dashboardEnabled\":true}}",
                "00000000-0000-4000-8000-000000000303", incomeRevision);
        assertEquals(200, visibility.statusCode());
        assertTrue(JSON.readTree(visibility.body()).at("/data/metric/visibility/hidden").asBoolean());
        HttpResponse<String> dashboard = send("GET", "/api/v1/dashboard?granularity=MONTH&anchor=2026-09-14", null, null, null);
        assertEquals(200, dashboard.statusCode()); assertEquals(8, JSON.readTree(dashboard.body()).at("/data/cards").size());
        HttpResponse<String> layout = send("GET", "/api/v1/dashboard/layout", null, null, null);
        assertEquals(200, layout.statusCode()); String layoutRevision = JSON.readTree(layout.body()).at("/data/layout/revision").asText();
        assertTrue(layout.headers().firstValue("etag").orElse("").contains(layoutRevision));
        String id = "custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b3";
        String body = "{\"id\":\"" + id + "\",\"name\":\"HTTP指标\",\"description\":\"\",\"displayFormat\":\"NUMBER\",\"precision\":2,\"visibility\":{\"hidden\":false,\"dashboardEnabled\":true},\"formula\":{\"ast\":{\"schemaVersion\":1,\"root\":{\"kind\":\"constant\",\"value\":\"12.5\"}}}}";
        HttpResponse<String> created = send("POST", "/api/v1/metrics", body, "00000000-0000-4000-8000-000000000301", null);
        assertEquals(201, created.statusCode()); assertEquals(id, JSON.readTree(created.body()).at("/data/metric/id").asText());
        assertEquals("CONSTANT", JSON.readTree(created.body()).at("/data/metric/formula/ast/root/kind").asText());
        assertEquals("constant", JSON.readTree(created.body()).at("/data/metric/formula/tokens/0/type").asText());
        HttpResponse<String> replay = send("POST", "/api/v1/metrics", body, "00000000-0000-4000-8000-000000000301", null);
        assertEquals(created.body(), replay.body());
        String formulaBody = "{\"candidateMetricId\":\"" + id
                + "\",\"displayFormat\":\"NUMBER\",\"formula\":{\"ast\":{\"schemaVersion\":1,\"root\":{\"kind\":\"constant\",\"value\":\"12.5\"}}},\"granularity\":\"MONTH\",\"anchor\":\"2026-09-14\"}";
        HttpResponse<String> validated = send("POST", "/api/v1/formulas/validate", formulaBody, null, null);
        assertEquals(200, validated.statusCode()); assertTrue(JSON.readTree(validated.body()).at("/data/valid").asBoolean());
        HttpResponse<String> preview = send("POST", "/api/v1/formulas/preview", formulaBody, null, null);
        assertEquals(200, preview.statusCode()); assertEquals("12.5", JSON.readTree(preview.body()).at("/data/value/value").asText());
        HttpResponse<String> rejectedTokens = send("POST", "/api/v1/formulas/validate",
                "{\"candidateMetricId\":\"" + id
                        + "\",\"displayFormat\":\"NUMBER\",\"formula\":{\"ast\":{\"schemaVersion\":1,\"root\":{\"kind\":\"CONSTANT\",\"value\":\"12.5\"}},\"tokens\":[]},\"granularity\":\"MONTH\",\"anchor\":\"2026-09-14\"}", null, null);
        assertEquals(400, rejectedTokens.statusCode());
        assertTrue(JSON.readTree(rejectedTokens.body()).at("/error/fieldErrors/formula.tokens").asText().length() > 0);
        HttpResponse<String> versions = send("GET", "/api/v1/formulas/" + id + "/versions", null, null, null);
        assertEquals(200, versions.statusCode()); assertEquals(1, JSON.readTree(versions.body()).at("/data/items").size());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String revision) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.origin() + path))
                .header("X-Request-Id", "00000000-0000-4000-8000-000000000302");
        if (session == null) session = new BrowserTestSession(server.origin());
        session.apply(builder);
        if (key != null) builder.header("Idempotency-Key", key);
        if (revision != null) builder.header("If-Match", "\"" + revision + "\"");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
