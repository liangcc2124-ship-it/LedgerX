package com.ledgerx.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildMetadata {
    private static final String DEFAULT_VERSION = "0.1.0-SNAPSHOT";

    private BuildMetadata() {
    }

    public static String applicationVersion() {
        Properties properties = new Properties();
        try (InputStream input = BuildMetadata.class.getResourceAsStream("/build.properties")) {
            if (input != null) {
                properties.load(input);
                String value = properties.getProperty("application.version");
                if (value != null && !value.trim().isEmpty() && !value.contains("${")) {
                    return value.trim();
                }
            }
        } catch (IOException ignored) {
            // The fixed fallback is the same version declared in pom.xml for dev/test classpaths.
        }
        return DEFAULT_VERSION;
    }
}
