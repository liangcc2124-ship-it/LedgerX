package com.ledgerx.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Provides the same cookie/CSRF session used by a browser to HTTP integration tests. */
public final class BrowserTestSession {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<LedgerHttpServer, BrowserTestSession> SESSIONS = new WeakHashMap<>();

    private final String origin;
    private String cookie;
    private String csrf;

    BrowserTestSession(String origin) {
        this.origin = origin;
    }

    public static BrowserTestSession forServer(LedgerHttpServer server) {
        synchronized (SESSIONS) {
            return SESSIONS.computeIfAbsent(server, ignored -> new BrowserTestSession(server.origin()));
        }
    }

    public void apply(HttpRequest.Builder builder) {
        try {
            ensureSession();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not establish a browser test session", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not establish a browser test session", ex);
        }
        builder.header("Cookie", cookie)
                .header("Origin", origin)
                .header("X-LedgerX-CSRF", csrf);
    }

    private void ensureSession() throws IOException, InterruptedException {
        if (cookie != null && csrf != null) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/api/v1/system/session"))
                .timeout(Duration.ofSeconds(3))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Browser session bootstrap returned HTTP " + response.statusCode());
        }
        String setCookie = response.headers().firstValue("set-cookie")
                .orElseThrow(() -> new IOException("Browser session cookie was not issued"));
        cookie = setCookie.substring(0, setCookie.indexOf(';'));
        JsonNode data = JSON.readTree(response.body()).at("/data");
        csrf = data.path("csrfToken").asText();
        if (!"browser".equals(data.path("authMode").asText()) || csrf.isEmpty()) {
            throw new IOException("Browser session bootstrap response is incomplete");
        }
    }
}
