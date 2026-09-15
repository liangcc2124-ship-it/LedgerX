package com.ledgerx.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

public final class SessionToken {
    public static final String ENVIRONMENT_NAME = "LEDGERX_SESSION_TOKEN";
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private SessionToken() {
    }

    public static String fromEnvironment(Map<String, String> environment) {
        return require(environment.get(ENVIRONMENT_NAME));
    }

    public static String require(String token) {
        if (!isValid(token)) {
            throw new IllegalArgumentException("LEDGERX_SESSION_TOKEN must be 32-byte unpadded base64url");
        }
        return token;
    }

    public static boolean isValid(String token) {
        if (token == null || !FORMAT.matcher(token).matches()) {
            return false;
        }
        try {
            int padding = (4 - (token.length() % 4)) % 4;
            byte[] decoded = Base64.getUrlDecoder().decode(token + "=".repeat(padding));
            return decoded.length == 32
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean matches(String expected, String providedAuthorization) {
        if (providedAuthorization == null || !providedAuthorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expectedBytes = ("Bearer " + expected).getBytes(StandardCharsets.US_ASCII);
        byte[] providedBytes = providedAuthorization.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
