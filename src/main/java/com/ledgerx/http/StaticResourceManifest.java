package com.ledgerx.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.regex.Pattern;

public final class StaticResourceManifest {
    private static final Pattern ENCODED_TRAVERSAL = Pattern.compile("(?i)%2e|%2f|%5c|%00");
    private final Path directory;
    private final ClassLoader classLoader;
    private final Map<String, String> entries;

    private StaticResourceManifest(Path directory, ClassLoader classLoader, Map<String, String> entries) {
        this.directory = directory;
        this.classLoader = classLoader;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        validateEntries(this.entries);
    }

    public static StaticResourceManifest fromClasspath() {
        Properties properties = new Properties();
        ClassLoader loader = StaticResourceManifest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream("web/manifest.properties")) {
            if (input == null) {
                throw new IllegalStateException("web manifest is missing");
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("web manifest cannot be read", ex);
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            entries.put(name, properties.getProperty(name));
        }
        return new StaticResourceManifest(null, loader, entries);
    }

    public static StaticResourceManifest forDirectory(Path root, Map<String, String> entries) {
        if (root == null || entries == null) {
            throw new IllegalArgumentException("root and entries are required");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("web root must be a directory");
        }
        return new StaticResourceManifest(normalized, null, entries);
    }

    /** Builds a restricted manifest from a controlled Vue dist directory. */
    public static StaticResourceManifest fromDirectory(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("web root must be a non-symlink directory");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(normalized)) {
            ArrayList<Path> files = new ArrayList<>();
            paths.forEach(files::add);
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("web root contains an unsafe file");
                }
                Path relative = normalized.relativize(file);
                String logical = relative.toString().replace('\\', '/');
                if (!"index.html".equals(logical) && !logical.startsWith("assets/")) {
                    throw new IllegalArgumentException("web root contains an unsupported file");
                }
                entries.put(logical, logical);
            }
        }
        return new StaticResourceManifest(normalized, null, entries);
    }

    public Resource open(String rawPath, String decodedPath) throws IOException {
        String logicalPath = logicalPath(rawPath, decodedPath);
        if (logicalPath == null) {
            return null;
        }
        String resourceName = entries.get(logicalPath);
        if (resourceName == null) {
            return null;
        }
        byte[] content;
        if (directory != null) {
            Path file = directory.resolve(resourceName).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            content = Files.readAllBytes(file);
        } else {
            String classpathName = "web/" + resourceName;
            try (InputStream input = classLoader.getResourceAsStream(classpathName)) {
                if (input == null) {
                    return null;
                }
                content = readAll(input);
            }
        }
        return new Resource(content, mimeType(resourceName), logicalPath.startsWith("assets/"));
    }

    private String logicalPath(String rawPath, String decodedPath) {
        if (rawPath == null || decodedPath == null || rawPath.indexOf('\\') >= 0
                || decodedPath.indexOf('\\') >= 0 || ENCODED_TRAVERSAL.matcher(rawPath).find()
                || !decodedPath.startsWith("/")) {
            return null;
        }
        if ("/".equals(decodedPath)) {
            return "index.html";
        }
        String path = decodedPath.substring(1);
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        if (!path.startsWith("assets/") || path.length() == 7) {
            return null;
        }
        return path;
    }

    private static void validateEntries(Map<String, String> entries) {
        if (!entries.containsKey("index.html")) {
            throw new IllegalArgumentException("manifest must contain index.html");
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String logical = entry.getKey();
            String resource = entry.getValue();
            if (logical == null || resource == null || logical.contains("\\") || resource.contains("\\")
                    || !logical.equals(logical.trim()) || !resource.equals(resource.trim())) {
                throw new IllegalArgumentException("manifest contains an invalid path");
            }
            if (!("index.html".equals(logical) || logical.startsWith("assets/"))) {
                throw new IllegalArgumentException("manifest contains an unsupported entry");
            }
            Path normalized = Path.of(resource).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..") || resource.contains("..")) {
                throw new IllegalArgumentException("manifest contains traversal");
            }
            mimeType(resource);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        return input.readAllBytes();
    }

    private static String mimeType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        throw new IllegalArgumentException("manifest resource MIME type is not allowlisted");
    }

    public static final class Resource {
        private final byte[] content;
        private final String contentType;
        private final boolean immutableAsset;

        private Resource(byte[] content, String contentType, boolean immutableAsset) {
            this.content = content;
            this.contentType = contentType;
            this.immutableAsset = immutableAsset;
        }

        public byte[] getContent() {
            return content.clone();
        }

        public String getContentType() {
            return contentType;
        }

        public String getCacheControl() {
            return immutableAsset ? "public, max-age=31536000, immutable" : "no-cache";
        }
    }
}
