package com.ledgerx.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** One browser session shared by tabs for the lifetime of this local server process. */
final class BrowserSession {
    static final String COOKIE_NAME = "ledgerx_session";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String cookieValue;
    private final String csrfToken;

    BrowserSession() {
        this.cookieValue = randomToken();
        this.csrfToken = randomToken();
    }

    String csrfToken() {
        return csrfToken;
    }

    boolean matchesCookie(String provided) {
        return constantTimeEquals(cookieValue, provided);
    }

    boolean matchesCsrf(String provided) {
        return constantTimeEquals(csrfToken, provided);
    }

    String setCookieHeader() {
        return COOKIE_NAME + "=" + cookieValue + "; HttpOnly; SameSite=Strict; Path=/";
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                provided.getBytes(StandardCharsets.US_ASCII));
    }
}
