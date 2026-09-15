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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
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
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
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
    void statusEnforcesRequestIdAuthenticationHostAndOrigin(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        AtomicInteger calls = new AtomicInteger();
        SystemStatusProvider provider = () -> {
            calls.incrementAndGet();
            return new InitialSystemStatusProvider(BuildMetadata.applicationVersion()).current();
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        server = newServer(provider, null, temp, 2, 2, output);
        server.start();

        HttpResponse<String> missingRequestId = send("GET", "/api/v1/system/status", null, null, TOKEN, null);
        assertEquals(400, missingRequestId.statusCode());
        assertEquals("INVALID_REQUEST_ID", errorCode(missingRequestId));
        assertNotNull(missingRequestId.headers().firstValue("x-request-id").orElse(null));
        assertEquals(0, calls.get());

        HttpResponse<String> invalidRequestId = send("GET", "/api/v1/system/status", null, null, TOKEN,
                "not-a-uuid");
        assertEquals(400, invalidRequestId.statusCode());
        assertEquals("INVALID_REQUEST_ID", errorCode(invalidRequestId));
        assertNotNull(invalidRequestId.headers().firstValue("x-request-id").orElse(null));
        assertEquals(0, calls.get());

        HttpResponse<String> wrongToken = send("GET", "/api/v1/system/status", null, null,
                TOKEN.substring(0, TOKEN.length() - 1) + "_", UUID.randomUUID().toString());
        assertEquals(401, wrongToken.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", errorCode(wrongToken));
        assertFalse(wrongToken.body().contains(TOKEN));
        assertEquals(0, calls.get());

        HttpResponse<String> missingToken = send("GET", "/api/v1/system/status", null, null,
                null, UUID.randomUUID().toString());
        assertEquals(401, missingToken.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", errorCode(missingToken));
        assertEquals(0, calls.get());

        String requestId = UUID.randomUUID().toString();
        HttpResponse<String> status = send("GET", "/api/v1/system/status", null, null, TOKEN, requestId);
        assertEquals(200, status.statusCode());
        assertEquals(requestId, status.headers().firstValue("x-request-id").orElse(""));
        assertEquals("1.0", status.headers().firstValue("ledgerx-api-version").orElse(""));
        assertTrue(status.body().contains("\"state\":\"STARTING\""));
        assertTrue(status.body().contains("\"capabilities\":[\"system.status\"]"));
        assertFalse(status.body().contains(TOKEN));
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(
                mapper.readTree(Files.readString(
                        Path.of("docs/contracts/api-v1/system/status-starting.json"), StandardCharsets.UTF_8)),
                mapper.readTree(status.body()));
        assertEquals(1, calls.get());

        CompletableFuture<HttpResponse<String>> concurrentA = CompletableFuture.supplyAsync(
                () -> sendUnchecked("GET", "/api/v1/system/status", TOKEN));
        CompletableFuture<HttpResponse<String>> concurrentB = CompletableFuture.supplyAsync(
                () -> sendUnchecked("GET", "/api/v1/system/status", TOKEN));
        assertEquals(200, concurrentA.get(2, TimeUnit.SECONDS).statusCode());
        assertEquals(200, concurrentB.get(2, TimeUnit.SECONDS).statusCode());
        assertEquals(3, calls.get());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(TOKEN));

        HttpResponse<String> wrongHost = sendWithHost("GET", "/api/v1/system/status", null, null, TOKEN,
                UUID.randomUUID().toString(), "localhost:" + server.address().getPort(), null);
        assertEquals(403, wrongHost.statusCode());
        assertEquals("REQUEST_ORIGIN_FORBIDDEN", errorCode(wrongHost));
        assertEquals(3, calls.get());

        HttpResponse<String> wrongOrigin = sendWithHost("GET", "/api/v1/system/status", null, null, TOKEN,
                UUID.randomUUID().toString(), server.address().getHostString() + ":" + server.address().getPort(),
                "http://evil.invalid");
        assertEquals(403, wrongOrigin.statusCode());
        assertEquals(3, calls.get());

        HttpResponse<String> wrongMethod = sendWith("POST", "/api/v1/system/status", "", "", TOKEN,
                UUID.randomUUID().toString(), server.origin());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET", wrongMethod.headers().firstValue("allow").orElse(""));
        assertEquals(3, calls.get());
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

        HttpResponse<String> mediaType = sendWith("POST", "/api/v1/system/status", "{}", "text/plain", TOKEN,
                UUID.randomUUID().toString(), server.origin());
        assertEquals(415, mediaType.statusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", errorCode(mediaType));

        HttpResponse<String> malformed = sendWith("POST", "/api/v1/system/status", "{", "application/json", TOKEN,
                UUID.randomUUID().toString(), server.origin());
        assertEquals(400, malformed.statusCode());
        assertEquals("INVALID_JSON", errorCode(malformed));

        byte[] oversized = new byte[LedgerHttpServer.MAX_JSON_BODY_BYTES + 1];
        HttpRequest request = request("POST", "/api/v1/system/status", oversized, "application/json", TOKEN,
                UUID.randomUUID().toString(), server.origin());
        HttpResponse<String> tooLarge = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, tooLarge.statusCode());
        assertEquals("PAYLOAD_TOO_LARGE", errorCode(tooLarge));
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
                return send("GET", "/api/v1/system/status", null, null, TOKEN, UUID.randomUUID().toString());
            } catch (IOException | InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        });
        assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
        HttpResponse<String> second = send("GET", "/api/v1/system/status", null, null, TOKEN,
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
    void tokenFormatIsStrict() {
        assertTrue(SessionToken.isValid(TOKEN));
        assertFalse(SessionToken.isValid("short"));
        assertFalse(SessionToken.isValid(TOKEN + "="));
        assertFalse(SessionToken.isValid(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31])));
        assertEquals(TOKEN, SessionToken.fromEnvironment(Collections.singletonMap(
                SessionToken.ENVIRONMENT_NAME, TOKEN)));
        assertThrows(IllegalArgumentException.class, () -> SessionToken.fromEnvironment(Collections.emptyMap()));
    }

    @Test
    void nonLoopbackBindIsRejected(@TempDir Path temp) throws Exception {
        prepareWebRoot(temp);
        StaticResourceManifest manifest = StaticResourceManifest.forDirectory(
                temp, Map.of("index.html", "index.html", "assets/app.js", "assets/app.js"));
        assertThrows(IllegalArgumentException.class, () -> new LedgerHttpServer(
                TOKEN,
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
                TOKEN,
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
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        if (origin != null) {
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
