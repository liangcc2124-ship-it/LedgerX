package com.ledgerx.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.system.InitialSystemStatusProvider;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerHttpServerTest {
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private LedgerHttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void readinessIsWrittenAtStartAndStaticResourcesAreRestricted(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("assets"));
        Files.writeString(temp.resolve("index.html"), "<h1>test</h1>", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("assets/app.js"), "console.log('test');", StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server = newServer(null, "0.1.0-SNAPSHOT", temp, 2, 2, output);

        assertEquals("", output.toString(StandardCharsets.UTF_8));
        server.start();
        String readiness = output.toString(StandardCharsets.UTF_8).trim();
        assertEquals("LEDGERX_READY {\"port\":" + server.address().getPort()
                        + ",\"protocol\":\"1\"}", readiness);
        String writtenReadiness = output.toString(StandardCharsets.UTF_8);

        HttpResponse<String> live = send("GET", "/health/live", null, null, null, null);
        assertEquals(200, live.statusCode());
        assertEquals("{\"status\":\"UP\"}", live.body());
        HttpResponse<String> liveMethod = send("POST", "/health/live", null, null, null, null);
        assertEquals(405, liveMethod.statusCode());
        assertEquals("GET", liveMethod.headers().firstValue("allow").orElse(""));

        HttpResponse<String> index = send("GET", "/", null, null, null, null);
        assertEquals(200, index.statusCode());
        assertEquals("<h1>test</h1>", index.body());
        assertTrue(index.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        assertEquals("no-cache", index.headers().firstValue("cache-control").orElse(""));

        HttpResponse<String> assetHead = send("HEAD", "/assets/app.js", null, null, null, null);
        assertEquals(200, assetHead.statusCode());
        assertEquals("", assetHead.body());
        assertEquals("public, max-age=31536000, immutable",
                assetHead.headers().firstValue("cache-control").orElse(""));
        assertEquals(String.valueOf("console.log('test');".getBytes(StandardCharsets.UTF_8).length),
                assetHead.headers().firstValue("content-length").orElse(""));

        assertEquals(404, send("GET", "/unknown.js", null, null, null, null).statusCode());
        assertEquals(404, send("GET", "/assets/%2e%2e/index.html", null, null, null, null).statusCode());
        assertEquals(404, send("GET", "/assets/%5c..%5cindex.html", null, null, null, null).statusCode());
        assertEquals(405, send("POST", "/", "{}", "application/json", null, null).statusCode());
        assertEquals(writtenReadiness, output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void requestIdentifiersAreValidatedBeforeApplicationAccess(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        AtomicInteger calls = new AtomicInteger();
        SystemStatusProvider provider = () -> {
            calls.incrementAndGet();
            return new InitialSystemStatusProvider(BuildMetadata.applicationVersion()).current();
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server = newServer(provider, null, temp, 2, 2, output);
        server.start();

        HttpResponse<String> missingRequestId = send("GET", "/api/v1/system/status", null, null, null, null);
        assertEquals(400, missingRequestId.statusCode());
        assertEquals("INVALID_REQUEST_ID", errorCode(missingRequestId));
        assertNotNull(missingRequestId.headers().firstValue("x-request-id").orElse(null));
        assertEquals(0, calls.get());

        HttpResponse<String> invalidRequestId = send("GET", "/api/v1/system/status", null, null, null,
                "not-a-uuid");
        assertEquals(400, invalidRequestId.statusCode());
        assertEquals("INVALID_REQUEST_ID", errorCode(invalidRequestId));
        assertNotNull(invalidRequestId.headers().firstValue("x-request-id").orElse(null));
        assertEquals(0, calls.get());

        HttpResponse<String> status = send("GET", "/api/v1/system/status", null, null, "browser",
                UUID.randomUUID().toString());
        assertEquals(200, status.statusCode());
        assertEquals("1.0", status.headers().firstValue("ledgerx-api-version").orElse(""));
        assertTrue(status.body().contains("\"state\":\"STARTING\""));
        assertTrue(status.body().contains("\"capabilities\":[\"system.status\"]"));
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(
                mapper.readTree(Files.readString(
                        Path.of("docs/contracts/api-v1/system/status-starting.json"), StandardCharsets.UTF_8)),
                mapper.readTree(status.body()));
        assertEquals(1, calls.get());
        assertEquals(1, calls.get());
    }

    @Test
    void browserSessionRequiresCookieHostOriginAndCsrf(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        server = LedgerHttpServer.forBrowser(
                new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.forDirectory(temp, Map.of("index.html", "index.html",
                        "assets/app.js", "assets/app.js")),
                new InitialSystemStatusProvider(BuildMetadata.applicationVersion()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), 2, 2);
        server.start();

        String requestId = UUID.randomUUID().toString();
        HttpResponse<String> bootstrap = sendBrowser("GET", "/api/v1/system/session", null, null,
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(), requestId);
        assertEquals(200, bootstrap.statusCode());
        assertEquals("no-store", bootstrap.headers().firstValue("cache-control").orElse(""));
        String setCookie = bootstrap.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(setCookie.startsWith("ledgerx_session="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Path=/"));
        assertFalse(setCookie.toLowerCase(java.util.Locale.ROOT).contains("domain="));
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        String csrf = new ObjectMapper().readTree(bootstrap.body()).path("data").path("csrfToken").asText();
        assertTrue(csrf.matches("[A-Za-z0-9_-]{43}"));
        assertFalse(bootstrap.body().contains(cookie.substring(cookie.indexOf('=') + 1)));

        HttpResponse<String> missingCookie = sendBrowser("GET", "/api/v1/system/status", null, null,
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString());
        assertEquals(401, missingCookie.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", errorCode(missingCookie));

        HttpRequest bearerOnly = HttpRequest.newBuilder()
                .uri(URI.create(server.origin() + "/api/v1/system/status"))
                .header("Authorization", "Bearer legacy-token")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .GET().build();
        HttpResponse<String> bearerRejected = CLIENT.send(bearerOnly, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, bearerRejected.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", errorCode(bearerRejected));

        HttpResponse<String> status = sendBrowser("GET", "/api/v1/system/status", cookie, null,
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString());
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"state\":\"STARTING\""));

        String profileJson = "{\"id\":\"7d8ef560-16d8-4bd8-83f3-8e60310928f8\",\"name\":\"test\"}";
        HttpResponse<String> missingCsrf = sendBrowser("POST", "/api/v1/profiles", cookie, null,
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString(), profileJson);
        assertEquals(403, missingCsrf.statusCode());
        assertEquals("INVALID_CSRF_TOKEN", errorCode(missingCsrf));

        HttpResponse<String> wrongCsrf = sendBrowser("POST", "/api/v1/profiles", cookie, "wrong",
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString(), profileJson);
        assertEquals(403, wrongCsrf.statusCode());
        assertEquals("INVALID_CSRF_TOKEN", errorCode(wrongCsrf));

        HttpResponse<String> validCsrf = sendBrowser("POST", "/api/v1/profiles", cookie, csrf,
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString(), profileJson);
        assertEquals(423, validCsrf.statusCode());
        assertEquals("RECOVERY_REQUIRED", errorCode(validCsrf));

        HttpResponse<String> wrongOrigin = sendBrowser("GET", "/api/v1/system/status", cookie, null,
                "http://evil.invalid", server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString());
        assertEquals(403, wrongOrigin.statusCode());
        assertEquals("REQUEST_ORIGIN_FORBIDDEN", errorCode(wrongOrigin));

        HttpResponse<String> wrongHostBootstrap = sendBrowser("GET", "/api/v1/system/session", null, null,
                server.origin(), "localhost:" + server.address().getPort(), UUID.randomUUID().toString());
        assertEquals(403, wrongHostBootstrap.statusCode());

        HttpResponse<String> page = send("GET", "/", null, null, null, null);
        assertTrue(page.headers().firstValue("content-security-policy").orElse("").contains("frame-ancestors 'none'"));
        assertEquals("nosniff", page.headers().firstValue("x-content-type-options").orElse(""));
    }

    @Test
    void malformedAndOversizedBodiesAreRejectedBeforeApplication(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        AtomicInteger calls = new AtomicInteger();
        server = newServer(() -> {
            calls.incrementAndGet();
            return new InitialSystemStatusProvider(BuildMetadata.applicationVersion()).current();
        }, null, temp, 2, 2, new ByteArrayOutputStream());
        server.start();

        HttpResponse<String> mediaType = sendWith("POST", "/api/v1/system/status", "{}", "text/plain", "browser",
                UUID.randomUUID().toString(), server.origin());
        assertEquals(415, mediaType.statusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", errorCode(mediaType));

        HttpResponse<String> malformed = sendWith("POST", "/api/v1/system/status", "{", "application/json", "browser",
                UUID.randomUUID().toString(), server.origin());
        assertEquals(400, malformed.statusCode());
        assertEquals("INVALID_JSON", errorCode(malformed));

        HttpResponse<String> bootstrap = sendBrowser("GET", "/api/v1/system/session", null, null,
                server.origin(), server.address().getHostString() + ":" + server.address().getPort(),
                UUID.randomUUID().toString());
        assertEquals(200, bootstrap.statusCode());
        String cookie = bootstrap.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";", 2)[0];
        String csrf = new ObjectMapper().readTree(bootstrap.body()).path("data").path("csrfToken").asText();
        String host = server.address().getHostString() + ":" + server.address().getPort();
        String oversizedHeaders = "POST /api/v1/system/status HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Origin: " + server.origin() + "\r\n"
                + "Cookie: " + cookie + "\r\n"
                + "X-LedgerX-CSRF: " + csrf + "\r\n"
                + "X-Request-Id: " + UUID.randomUUID() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + (LedgerHttpServer.MAX_JSON_BODY_BYTES + 1) + "\r\n"
                + "Connection: close\r\n\r\n";
        ByteArrayOutputStream oversizedResponse = new ByteArrayOutputStream();
        try (Socket rawClient = new Socket()) {
            rawClient.connect(new InetSocketAddress(server.address().getAddress(), server.address().getPort()), 2_000);
            rawClient.setSoTimeout(3_000);
            rawClient.getOutputStream().write(oversizedHeaders.getBytes(StandardCharsets.US_ASCII));
            rawClient.getOutputStream().flush();
            rawClient.shutdownOutput();
            rawClient.getInputStream().transferTo(oversizedResponse);
        }
        String oversizedResult = oversizedResponse.toString(StandardCharsets.UTF_8);
        assertTrue(oversizedResult.startsWith("HTTP/1.1 413 "), oversizedResult);
        assertTrue(oversizedResult.contains("\"code\":\"PAYLOAD_TOO_LARGE\""), oversizedResult);
        assertEquals(0, calls.get());
    }

    @Test
    void boundedExecutorReturns429AndStopClosesPort(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        BlockingProvider provider = new BlockingProvider();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server = newServer(provider, null, temp, 1, 0, output);
        server.start();

        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return send("GET", "/api/v1/system/status", null, null, "browser", UUID.randomUUID().toString());
            } catch (IOException | InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        });
        assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
        HttpResponse<String> second = send("GET", "/api/v1/system/status", null, null, "browser",
                UUID.randomUUID().toString());
        assertEquals(429, second.statusCode());
        assertEquals("RATE_LIMITED", errorCode(second));

        provider.release.countDown();
        assertEquals(200, first.get(2, TimeUnit.SECONDS).statusCode());
        server.stop(Duration.ofSeconds(2));
        assertTrue(server.isExecutorTerminated());
        assertThrows(IOException.class,
                () -> CLIENT.send(request("GET", "/health/live", null, null, null, null, server.origin()),
                        HttpResponse.BodyHandlers.ofString()));
        server = null;
    }

    @Test
    void nonLoopbackBindIsRejected(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        StaticResourceManifest manifest = StaticResourceManifest.forDirectory(
                temp, Map.of("index.html", "index.html", "assets/app.js", "assets/app.js"));
        assertThrows(IllegalArgumentException.class, () -> new LedgerHttpServer(
                new InetSocketAddress("0.0.0.0", 0),
                manifest,
                new InitialSystemStatusProvider(BuildMetadata.applicationVersion()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                1,
                0));
    }

    private LedgerHttpServer newServer(
            SystemStatusProvider provider,
            String ignoredVersion,
            Path root,
            int workers,
            int queueCapacity,
            ByteArrayOutputStream readiness) throws IOException {
        StaticResourceManifest manifest = StaticResourceManifest.forDirectory(
                root, Map.of("index.html", "index.html", "assets/app.js", "assets/app.js"));
        PrintStream stream = new PrintStream(readiness, true, StandardCharsets.UTF_8);
        SystemStatusProvider actual = provider;
        if (actual == null) {
            actual = new InitialSystemStatusProvider(ignoredVersion);
        }
        return new LedgerHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                manifest,
                actual,
                stream,
                workers,
                queueCapacity);
    }

    private void prepareWebRoot(Path root) throws IOException {
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("index.html"), "<h1>test</h1>", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("assets/app.js"), "console.log('test');", StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body,
            String contentType,
            String token,
            String requestId) throws IOException, InterruptedException {
        return sendWith(method, path, body, contentType, token, requestId, null);
    }

    private HttpResponse<String> sendUnchecked(String method, String path, String token) {
        try {
            return send(method, path, null, null, token, UUID.randomUUID().toString());
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private HttpResponse<String> sendWith(
            String method,
            String path,
            String body,
            String contentType,
            String token,
            String requestId,
            String origin) throws IOException, InterruptedException {
        return sendWithHost(method, path, body, contentType, token, requestId,
                server.address().getHostString() + ":" + server.address().getPort(), origin);
    }

    private HttpResponse<String> sendWithHost(
            String method,
            String path,
            String body,
            String contentType,
            String token,
            String requestId,
            String host,
            String origin) throws IOException, InterruptedException {
        HttpRequest request = request(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                contentType, token, requestId, origin == null ? server.origin() : origin, host);
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendBrowser(
            String method,
            String path,
            String cookie,
            String csrf,
            String origin,
            String host,
            String requestId) throws IOException, InterruptedException {
        return sendBrowser(method, path, cookie, csrf, origin, host, requestId, null);
    }

    private HttpResponse<String> sendBrowser(
            String method,
            String path,
            String cookie,
            String csrf,
            String origin,
            String host,
            String requestId,
            String body) throws IOException, InterruptedException {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(server.origin() + path))
                .timeout(Duration.ofSeconds(3)).header("Host", host)
                .header("X-Request-Id", requestId).header("Origin", origin)
                .method(method, bytes == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-LedgerX-CSRF", csrf);
        if (bytes != null) builder.header("Content-Type", "application/json");
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(
            String method,
            String path,
            byte[] body,
            String contentType,
            String token,
            String requestId,
            String origin) {
        return request(method, path, body, contentType, token, requestId, origin,
                server.address().getHostString() + ":" + server.address().getPort());
    }

    private HttpRequest request(
            String method,
            String path,
            byte[] body,
            String contentType,
            String token,
            String requestId,
            String origin,
            String host) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server.origin() + path))
                .timeout(Duration.ofSeconds(3))
                .method(method, publisher)
                .header("Host", host);
        if (token != null) BrowserTestSession.forServer(server).apply(builder);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        if (origin != null && token == null) {
            builder.header("Origin", origin);
        }
        return builder.build();
    }

    private String errorCode(HttpResponse<String> response) {
        String marker = "\"code\":\"";
        int start = response.body().indexOf(marker);
        assertTrue(start >= 0, response.body());
        int valueStart = start + marker.length();
        int valueEnd = response.body().indexOf('"', valueStart);
        return response.body().substring(valueStart, valueEnd);
    }

    private static final class BlockingProvider implements SystemStatusProvider {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SystemStatus current() {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test provider was not released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test provider interrupted", ex);
            }
            return new InitialSystemStatusProvider(BuildMetadata.applicationVersion()).current();
        }
    }
}
