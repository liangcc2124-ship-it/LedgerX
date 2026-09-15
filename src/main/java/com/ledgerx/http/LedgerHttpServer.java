package com.ledgerx.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.bootstrap.ApplicationRuntime;
import com.ledgerx.application.profile.ProfileApi;
import com.ledgerx.application.profile.ProfileApiResult;
import com.ledgerx.application.profile.ProfileException;
import com.ledgerx.application.profile.ProfileMutation;
import com.ledgerx.application.ledger.CategoryApi;
import com.ledgerx.application.ledger.CategoryApiResult;
import com.ledgerx.application.ledger.CategoryException;
import com.ledgerx.application.ledger.CategoryMutation;
import com.ledgerx.application.ledger.CategoryPatch;
import com.ledgerx.application.ledger.AccountApi;
import com.ledgerx.application.ledger.AccountApiResult;
import com.ledgerx.application.ledger.AccountException;
import com.ledgerx.application.ledger.AccountMutation;
import com.ledgerx.application.ledger.AccountPatch;
import com.ledgerx.application.ledger.RecordApi;
import com.ledgerx.application.ledger.RecordApiResult;
import com.ledgerx.application.ledger.RecordException;
import com.ledgerx.application.ledger.RecordMutation;
import com.ledgerx.application.ledger.RecordPatch;
import com.ledgerx.application.ledger.RecordQuery;
import com.ledgerx.application.ledger.RecordType;
import com.ledgerx.application.settings.SettingsApi;
import com.ledgerx.application.settings.SettingsApiResult;
import com.ledgerx.application.settings.SettingsException;
import com.ledgerx.application.settings.SettingsMutation;
import com.ledgerx.application.settings.SettingsPatch;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class LedgerHttpServer implements AutoCloseable {
    public static final int DEFAULT_WORKERS = 4;
    public static final int DEFAULT_QUEUE_CAPACITY = 32;
    public static final int MAX_JSON_BODY_BYTES = 1024 * 1024;
    private static final String API_VERSION = "1.0";
    private static final String READY_PREFIX = "LEDGERX_READY ";
    private static final String WEB_ROOT_ENVIRONMENT_NAME = "LEDGERX_WEB_ROOT";
    private static final Pattern REQUEST_ID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    private final HttpServer server;
    private final String sessionToken;
    private final StaticResourceManifest resources;
    private final SystemStatusProvider statusProvider;
    private final ProfileApi profileApi;
    private final SettingsApi settingsApi;
    private final CategoryApi categoryApi;
    private final AccountApi accountApi;
    private final RecordApi recordApi;
    private final PrintStream readinessOut;
    private final BoundedRequestExecutor executor;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean readinessWritten = new AtomicBoolean();

    public LedgerHttpServer(
            String sessionToken,
            InetSocketAddress bindAddress,
            StaticResourceManifest resources,
            SystemStatusProvider statusProvider,
            PrintStream readinessOut,
            int workers,
            int queueCapacity) throws IOException {
        this(sessionToken, bindAddress, resources, statusProvider,
                statusProvider instanceof ProfileApi ? (ProfileApi) statusProvider : null,
                statusProvider instanceof SettingsApi ? (SettingsApi) statusProvider : null,
                statusProvider instanceof CategoryApi ? (CategoryApi) statusProvider : null,
                statusProvider instanceof AccountApi ? (AccountApi) statusProvider : null,
                statusProvider instanceof RecordApi ? (RecordApi) statusProvider : null,
                readinessOut, workers, queueCapacity);
    }

    public LedgerHttpServer(
            String sessionToken,
            InetSocketAddress bindAddress,
            StaticResourceManifest resources,
            SystemStatusProvider statusProvider,
            ProfileApi profileApi,
            PrintStream readinessOut,
            int workers,
            int queueCapacity) throws IOException {
        this(sessionToken, bindAddress, resources, statusProvider, profileApi,
                statusProvider instanceof SettingsApi ? (SettingsApi) statusProvider : null,
                statusProvider instanceof CategoryApi ? (CategoryApi) statusProvider : null,
                statusProvider instanceof AccountApi ? (AccountApi) statusProvider : null,
                statusProvider instanceof RecordApi ? (RecordApi) statusProvider : null,
                readinessOut, workers, queueCapacity);
    }

    public LedgerHttpServer(
            String sessionToken,
            InetSocketAddress bindAddress,
            StaticResourceManifest resources,
            SystemStatusProvider statusProvider,
            ProfileApi profileApi,
            SettingsApi settingsApi,
            PrintStream readinessOut,
            int workers,
            int queueCapacity) throws IOException {
        this(sessionToken, bindAddress, resources, statusProvider, profileApi, settingsApi,
                statusProvider instanceof CategoryApi ? (CategoryApi) statusProvider : null,
                statusProvider instanceof AccountApi ? (AccountApi) statusProvider : null,
                statusProvider instanceof RecordApi ? (RecordApi) statusProvider : null,
                readinessOut, workers, queueCapacity);
    }

    public LedgerHttpServer(
            String sessionToken,
            InetSocketAddress bindAddress,
            StaticResourceManifest resources,
            SystemStatusProvider statusProvider,
            ProfileApi profileApi,
            SettingsApi settingsApi,
            CategoryApi categoryApi,
            PrintStream readinessOut,
            int workers,
            int queueCapacity) throws IOException {
        this(sessionToken, bindAddress, resources, statusProvider, profileApi, settingsApi, categoryApi,
                statusProvider instanceof AccountApi ? (AccountApi) statusProvider : null,
                statusProvider instanceof RecordApi ? (RecordApi) statusProvider : null,
                readinessOut, workers, queueCapacity);
    }

    public LedgerHttpServer(
            String sessionToken,
            InetSocketAddress bindAddress,
            StaticResourceManifest resources,
            SystemStatusProvider statusProvider,
            ProfileApi profileApi,
            SettingsApi settingsApi,
            CategoryApi categoryApi,
            AccountApi accountApi,
            RecordApi recordApi,
            PrintStream readinessOut,
            int workers,
            int queueCapacity) throws IOException {
        this.sessionToken = SessionToken.require(sessionToken);
        validateBindAddress(bindAddress);
        this.resources = require(resources, "resources");
        this.statusProvider = require(statusProvider, "statusProvider");
        this.profileApi = profileApi;
        this.settingsApi = settingsApi;
        this.categoryApi = categoryApi;
        this.accountApi = accountApi;
        this.recordApi = recordApi;
        this.readinessOut = require(readinessOut, "readinessOut");
        this.executor = new BoundedRequestExecutor(workers, queueCapacity);
        this.objectMapper = new ObjectMapper();
        this.server = HttpServer.create(bindAddress, 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
        validateBoundAddress(this.server.getAddress());
    }

    public static LedgerHttpServer fromEnvironment(PrintStream readinessOut) throws IOException {
        String token = SessionToken.fromEnvironment(System.getenv());
        StaticResourceManifest resources = resourcesFromEnvironment();
        ApplicationRuntime runtime = ApplicationBootstrap.runtimeFromEnvironment(BuildMetadata.applicationVersion());
        return new LedgerHttpServer(
                token,
                new InetSocketAddress("127.0.0.1", 0),
                resources,
                runtime,
                runtime,
                readinessOut,
                DEFAULT_WORKERS,
                DEFAULT_QUEUE_CAPACITY);
    }

    private static StaticResourceManifest resourcesFromEnvironment() throws IOException {
        String configured = System.getenv(WEB_ROOT_ENVIRONMENT_NAME);
        if (configured == null || configured.trim().isEmpty()) {
            return StaticResourceManifest.fromClasspath();
        }
        try {
            Path root = Paths.get(configured);
            return StaticResourceManifest.fromDirectory(root);
        } catch (IllegalArgumentException ex) {
            throw new IOException("configured web root is invalid", ex);
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("server already started");
        }
        try {
            server.start();
            validateBoundAddress(server.getAddress());
            writeReadinessLine();
        } catch (RuntimeException ex) {
            started.set(false);
            executor.shutdown(Duration.ZERO);
            throw ex;
        }
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public String origin() {
        return "http://" + address().getHostString() + ":" + address().getPort();
    }

    public boolean isStarted() {
        return started.get();
    }

    boolean isExecutorTerminated() {
        return executor.isTerminated();
    }

    public void stop(Duration timeout) {
        if (started.compareAndSet(true, false)) {
            int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, timeout.toMillis() / 1000L));
            server.stop(seconds);
        }
        executor.shutdown(timeout);
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(2));
    }

    private void handle(HttpExchange exchange) {
        if (BoundedRequestExecutor.isOverloadedRequest()) {
            sendError(exchange, 429, "RATE_LIMITED", "请求过多，请稍后重试。", requestIdOrNew(exchange), true,
                    Collections.singletonMap("Retry-After", "1"));
            return;
        }
        try {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (path == null) {
                sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestIdOrNew(exchange), false,
                        Collections.emptyMap());
                return;
            }
            if ("/health/live".equals(path)) {
                handleHealth(exchange);
            } else if (path.startsWith("/api/v1/")) {
                handleApi(exchange, path);
            } else {
                handleStatic(exchange, uri.getRawPath(), path);
            }
        } catch (Exception ex) {
            sendError(exchange, 500, "INTERNAL_ERROR", "本地服务发生内部错误。", requestIdOrNew(exchange), false,
                    Collections.emptyMap());
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!networkAllowed(exchange, false)) {
            sendError(exchange, 403, "REQUEST_ORIGIN_FORBIDDEN", "请求来源不被允许。", requestIdOrNew(exchange), false,
                    Collections.emptyMap());
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不被允许。", requestIdOrNew(exchange), false,
                    Collections.singletonMap("Allow", "GET"));
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8), null, "no-store",
                Collections.emptyMap());
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        String requestId = validRequestId(exchange);
        if (requestId == null) {
            return;
        }
        if (!networkAllowed(exchange, isMutation(exchange.getRequestMethod()))) {
            sendError(exchange, 403, "REQUEST_ORIGIN_FORBIDDEN", "请求来源不被允许。", requestId, false,
                    Collections.emptyMap());
            return;
        }
        if (!SessionToken.matches(sessionToken, exchange.getRequestHeaders().getFirst("Authorization"))) {
            sendError(exchange, 401, "AUTHENTICATION_REQUIRED", "需要有效的本地会话认证。", requestId, false,
                    Collections.emptyMap());
            return;
        }
        ParsedBody body = validateBody(exchange, requestId);
        if (body == null) {
            return;
        }
        try {
            if ("/api/v1/system/status".equals(path)) {
                handleSystemStatus(exchange, requestId);
            } else if ("/api/v1/profiles".equals(path) || path.startsWith("/api/v1/profiles/")) {
                handleProfiles(exchange, path, body.json, requestId);
            } else if ("/api/v1/settings".equals(path)) {
                handleSettings(exchange, body.json, requestId);
            } else if ("/api/v1/categories".equals(path) || path.startsWith("/api/v1/categories/")) {
                handleCategories(exchange, path, body.json, requestId);
            } else if ("/api/v1/accounts".equals(path) || path.startsWith("/api/v1/accounts/")) {
                handleAccounts(exchange, path, body.json, requestId);
            } else if ("/api/v1/records".equals(path) || path.startsWith("/api/v1/records/")) {
                handleRecords(exchange, path, body.json, requestId);
            } else if (path.startsWith("/api/v1/operations/")) {
                handleOperation(exchange, path, requestId);
            } else {
                sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false,
                        Collections.emptyMap());
            }
        } catch (ProfileException ex) {
            sendError(exchange, ex.getHttpStatus(), ex.getCode(), ex.getMessage(), requestId, false,
                    ex.getFieldErrors(), ex.getDetails(), Collections.emptyMap());
        } catch (SettingsException ex) {
            sendError(exchange, ex.getHttpStatus(), ex.getCode(), ex.getMessage(), requestId, false,
                    ex.getFieldErrors(), ex.getDetails(), Collections.emptyMap());
        } catch (CategoryException ex) {
            sendError(exchange, ex.getHttpStatus(), ex.getCode(), ex.getMessage(), requestId, false,
                    ex.getFieldErrors(), ex.getDetails(), Collections.emptyMap());
        } catch (AccountException ex) {
            sendError(exchange, ex.getHttpStatus(), ex.getCode(), ex.getMessage(), requestId, false,
                    ex.getFieldErrors(), ex.getDetails(), Collections.emptyMap());
        } catch (RecordException ex) {
            sendError(exchange, ex.getHttpStatus(), ex.getCode(), ex.getMessage(), requestId, false,
                    ex.getFieldErrors(), ex.getDetails(), Collections.emptyMap());
        }
    }

    private void handleSystemStatus(HttpExchange exchange, String requestId) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不被允许。", requestId, false,
                    Collections.singletonMap("Allow", "GET"));
            return;
        }
        SystemStatus status = statusProvider.current();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiVersion", status.getApiVersion());
        data.put("applicationVersion", status.getApplicationVersion());
        data.put("schemaVersion", status.getSchemaVersion());
        data.put("backupFormatVersion", status.getBackupFormatVersion());
        data.put("activeProfileId", status.getActiveProfileId());
        data.put("state", status.getState());
        data.put("capabilities", status.getCapabilities());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataRevision", status.getDataRevision());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        response.put("meta", meta);
        sendJson(exchange, 200, json(response), requestId, "no-store", Collections.emptyMap());
    }

    private void handleProfiles(HttpExchange exchange, String path, JsonNode body, String requestId)
            throws IOException, ProfileException {
        if (profileApi == null) {
            throw new ProfileException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作用户空间。");
        }
        String method = exchange.getRequestMethod();
        if ("/api/v1/profiles".equals(path)) {
            if ("GET".equals(method)) {
                Map<String, String> query = singleQuery(exchange.getRequestURI(),
                        setOf("includeArchived", "limit", "cursor"));
                boolean includeArchived = parseBoolean(query.get("includeArchived"), "includeArchived", false);
                int limit = parseLimit(query.get("limit"));
                sendProfileResult(exchange, profileApi.list(includeArchived, limit, query.get("cursor")), requestId);
                return;
            }
            if ("POST".equals(method)) {
                JsonNode object = requireObject(body);
                String id = requiredText(object, "id");
                String name = requiredText(object, "name");
                Map<String, Object> canonical = new LinkedHashMap<>();
                canonical.put("id", id);
                canonical.put("name", name.trim());
                ProfileMutation mutation = mutation(exchange, "POST", path, canonical);
                sendProfileResult(exchange, profileApi.create(id, name, mutation), requestId);
                return;
            }
            methodNotAllowed(exchange, requestId, "GET, POST");
            return;
        }

        String profilePrefix = "/api/v1/profiles/";
        if (!path.startsWith(profilePrefix)) {
            sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false,
                    Collections.emptyMap());
            return;
        }
        String remaining = path.substring(profilePrefix.length());
        if (remaining.endsWith("/activate") && remaining.length() > "/activate".length()) {
            String id = remaining.substring(0, remaining.length() - "/activate".length());
            if (id.contains("/")) {
                sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false,
                        Collections.emptyMap());
                return;
            }
            if (!"POST".equals(method)) {
                methodNotAllowed(exchange, requestId, "POST");
                return;
            }
            requireEmptyObject(body);
            long revision = parseIfMatch(exchange);
            ProfileMutation mutation = mutation(exchange, "POST", path, Collections.emptyMap());
            sendProfileResult(exchange, profileApi.activate(id, revision, mutation), requestId);
            return;
        }
        if (remaining.isEmpty() || remaining.contains("/")) {
            sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false,
                    Collections.emptyMap());
            return;
        }
        if (!"DELETE".equals(method)) {
            methodNotAllowed(exchange, requestId, "DELETE");
            return;
        }
        if (body != null) {
            throw validation("body", "归档请求不能包含请求体。");
        }
        long revision = parseIfMatch(exchange);
        ProfileMutation mutation = mutation(exchange, "DELETE", path, Collections.emptyMap());
        sendProfileResult(exchange, profileApi.archive(remaining, revision, mutation), requestId);
    }

    private void handleOperation(HttpExchange exchange, String path, String requestId)
            throws IOException, ProfileException {
        if (profileApi == null) {
            throw new ProfileException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能查询操作。");
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, requestId, "GET");
            return;
        }
        String prefix = "/api/v1/operations/";
        String key = path.substring(prefix.length());
        if (key.isEmpty() || key.contains("/")) {
            sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false,
                    Collections.emptyMap());
            return;
        }
        sendProfileResult(exchange, profileApi.findOperation(key), requestId);
    }

    private void handleSettings(HttpExchange exchange, JsonNode body, String requestId)
            throws IOException, SettingsException {
        if (settingsApi == null) {
            throw new SettingsException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取或保存设置。");
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            throw settingsValidation("query", "设置接口不接受查询参数。");
        }
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            sendSettingsResult(exchange, settingsApi.read(), requestId);
            return;
        }
        if (!"PATCH".equals(method)) {
            methodNotAllowed(exchange, requestId, "GET, PATCH");
            return;
        }
        SettingsRequest request = parseSettingsRequest(body);
        long revision = parseSettingsIfMatch(exchange);
        SettingsMutation mutation = settingsMutation(exchange, request.canonicalBody);
        sendSettingsResult(exchange, settingsApi.update(revision, request.patch, mutation), requestId);
    }

    private void handleCategories(HttpExchange exchange, String path, JsonNode body, String requestId)
            throws IOException, CategoryException {
        if (categoryApi == null) throw new CategoryException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作分类。");
        String method = exchange.getRequestMethod();
        if ("/api/v1/categories".equals(path)) {
            if ("GET".equals(method)) {
                try {
                    Map<String, String> query = singleQuery(exchange.getRequestURI(), setOf("includeArchived", "limit", "cursor"));
                    sendCategoryResult(exchange, categoryApi.listCategories(parseBoolean(query.get("includeArchived"), "includeArchived", false),
                            parseLimit(query.get("limit")), query.get("cursor")), requestId);
                    return;
                } catch (ProfileException ex) {
                    throw categoryFromProfile(ex);
                }
            }
            if ("POST".equals(method)) {
                JsonNode object = categoryObject(body);
                String id = categoryText(object, "id"); String name = categoryText(object, "name");
                if (!object.has("parentId")) throw categoryValidation("parentId", "必须显式提供 parentId。");
                String parentId = categoryNullableUuid(object.get("parentId"), "parentId");
                Map<String,Object> canonical = new LinkedHashMap<>(); canonical.put("id", id); canonical.put("name", name.trim()); canonical.put("parentId", parentId);
                sendCategoryResult(exchange, categoryApi.createCategory(new CategoryPatch(id, name, parentId),
                        categoryMutation(exchange, "POST", path, canonical)), requestId); return;
            }
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不被允许。", requestId, false,
                    Collections.emptyMap(), Collections.emptyMap(), Collections.singletonMap("Allow", "GET, POST")); return;
        }
        String prefix = "/api/v1/categories/";
        if (!path.startsWith(prefix)) { sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false, Collections.emptyMap()); return; }
        String remaining = path.substring(prefix.length());
        if (remaining.endsWith("/merge")) {
            String targetId = remaining.substring(0, remaining.length() - "/merge".length());
            if (targetId.isEmpty() || targetId.contains("/")) { sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false, Collections.emptyMap()); return; }
            if (!"POST".equals(method)) { methodNotAllowed(exchange, requestId, "POST"); return; }
            JsonNode object = categoryObject(body); JsonNode sources = object.get("sources");
            if (sources == null || !sources.isArray() || sources.size() == 0 || sources.size() > 200) throw categoryValidation("sources", "sources 必须包含 1 到 200 项。");
            String[] ids = new String[sources.size()]; long[] revisions = new long[sources.size()]; List<Map<String,Object>> canonicalSources = new ArrayList<>();
            for (int i=0;i<sources.size();i++) { JsonNode source=sources.get(i); if (!source.isObject()) throw categoryValidation("sources["+i+"]", "来源项必须是对象。");
                ids[i]=categoryText(source,"id"); JsonNode rev=source.get("expectedRevision"); if(rev==null||!rev.isIntegralNumber()||rev.asLong()<0) throw categoryValidation("sources["+i+"].expectedRevision", "必须是非负整数。");
                revisions[i]=rev.asLong(); Map<String,Object> item=new LinkedHashMap<>(); item.put("id",ids[i]);item.put("expectedRevision",revisions[i]);canonicalSources.add(item); }
            Map<String,Object> canonical=new LinkedHashMap<>(); canonical.put("sources",canonicalSources);
            long expected=parseCategoryIfMatch(exchange); sendCategoryResult(exchange, categoryApi.mergeCategories(targetId, expected, ids, revisions,
                    categoryMutation(exchange, "POST", path, canonical)), requestId); return;
        }
        if (remaining.isEmpty() || remaining.contains("/")) { sendError(exchange, 404, "ROUTE_NOT_FOUND", "路由不存在。", requestId, false, Collections.emptyMap()); return; }
        long revision = parseCategoryIfMatch(exchange);
        if ("PUT".equals(method)) {
            JsonNode object=categoryObject(body); String name=categoryText(object,"name"); if(!object.has("parentId")) throw categoryValidation("parentId", "必须显式提供 parentId。");
            String parentId=categoryNullableUuid(object.get("parentId"),"parentId"); Map<String,Object> canonical=new LinkedHashMap<>();canonical.put("name",name.trim());canonical.put("parentId",parentId);
            sendCategoryResult(exchange, categoryApi.replaceCategory(remaining, revision, new CategoryPatch(remaining,name,parentId), categoryMutation(exchange,"PUT",path,canonical)), requestId); return;
        }
        if ("DELETE".equals(method)) {
            if(body!=null) throw categoryValidation("body","归档请求不能包含请求体。");
            Map<String,Object> canonical=Collections.emptyMap(); sendCategoryResult(exchange, categoryApi.archiveCategory(remaining, revision, categoryMutation(exchange,"DELETE",path,canonical)), requestId); return;
        }
        methodNotAllowed(exchange, requestId, "PUT, DELETE");
    }

    private void sendCategoryResult(HttpExchange exchange, CategoryApiResult result, String requestId) throws IOException {
        Map<String,String> headers=new LinkedHashMap<>(); if(result.getEtag()!=null)headers.put("ETag",result.getEtag()); if(result.getLocation()!=null)headers.put("Location",result.getLocation());
        sendJson(exchange,result.getStatus(),result.getResponseJson().getBytes(StandardCharsets.UTF_8),requestId,"no-store",headers);
    }

    private JsonNode categoryObject(JsonNode value) throws CategoryException { if(value==null||!value.isObject()) throw categoryValidation("body","请求体必须是 JSON 对象。"); return value; }
    private String categoryText(JsonNode object,String field) throws CategoryException { JsonNode value=object.get(field); if(value==null||!value.isTextual())throw categoryValidation(field,"必须提供字符串值。"); return value.textValue(); }
    private String categoryNullableUuid(JsonNode value,String field) throws CategoryException { if(value==null||value.isNull())return null; if(!value.isTextual()||!value.textValue().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw categoryValidation(field,"必须是小写 UUID 或 null。"); return value.textValue(); }
    private long parseCategoryIfMatch(HttpExchange exchange) throws CategoryException { String value=exchange.getRequestHeaders().getFirst("If-Match"); if(value==null||value.trim().isEmpty())throw new CategoryException(428,"PRECONDITION_REQUIRED","需要 If-Match 版本。"); if(!value.trim().matches("\\\"[0-9]+\\\""))throw categoryValidation("If-Match","必须是强 ETag。"); try{return Long.parseLong(value.trim().substring(1,value.trim().length()-1));}catch(NumberFormatException ex){throw categoryValidation("If-Match","必须是强 ETag。");} }
    private CategoryMutation categoryMutation(HttpExchange exchange,String method,String path,Map<String,Object> body) throws CategoryException { String key=exchange.getRequestHeaders().getFirst("Idempotency-Key"); if(key==null||key.trim().isEmpty())throw categoryValidation("Idempotency-Key","缺少幂等键。"); try{return new CategoryMutation(key,method,path,canonicalHash(body));}catch(JsonProcessingException ex){throw new CategoryException(500,"INTERNAL_ERROR","本地服务发生内部错误。");} }
    private static CategoryException categoryValidation(String field,String message){return new CategoryException(400,"VALIDATION_FAILED","请求参数无效。",Collections.singletonMap(field,message),Collections.emptyMap());}
    private static CategoryException categoryFromProfile(ProfileException ex){return new CategoryException(ex.getHttpStatus(),ex.getCode(),ex.getMessage(),ex.getFieldErrors(),ex.getDetails());}

    private void handleAccounts(HttpExchange exchange,String path,JsonNode body,String requestId)throws IOException,AccountException{
        if(accountApi==null)throw new AccountException(423,"RECOVERY_REQUIRED","数据恢复完成前不能操作账户。");String method=exchange.getRequestMethod();
        if("/api/v1/accounts".equals(path)){
            if("GET".equals(method)){try{Map<String,String> q=singleQuery(exchange.getRequestURI(),setOf("asOf","includeArchived","limit","cursor"));LocalDate asOf=accountDate(q.get("asOf"));sendAccountResult(exchange,accountApi.listAccounts(parseBoolean(q.get("includeArchived"),"includeArchived",false),asOf,parseLimit(q.get("limit")),q.get("cursor")),requestId);return;}catch(ProfileException ex){throw accountFromProfile(ex);}}
            if("POST".equals(method)){JsonNode o=accountObject(body);AccountPatch p=parseAccountPatch(o,true);Map<String,Object> canonical=accountCanonical(o,p,true);sendAccountResult(exchange,accountApi.createAccount(p,accountMutation(exchange,"POST",path,canonical)),requestId);return;}
            methodNotAllowed(exchange,requestId,"GET, POST");return;
        }
        String prefix="/api/v1/accounts/";if(!path.startsWith(prefix)){sendError(exchange,404,"ROUTE_NOT_FOUND","路由不存在。",requestId,false,Collections.emptyMap());return;}String id=path.substring(prefix.length());if(id.isEmpty()||id.contains("/")){sendError(exchange,404,"ROUTE_NOT_FOUND","路由不存在。",requestId,false,Collections.emptyMap());return;}long revision=parseAccountIfMatch(exchange);
        if("PUT".equals(method)){JsonNode o=accountObject(body);AccountPatch p=parseAccountPatch(o,false);Map<String,Object> canonical=accountCanonical(o,p,false);sendAccountResult(exchange,accountApi.replaceAccount(id,revision,p,accountMutation(exchange,"PUT",path,canonical)),requestId);return;}
        if("DELETE".equals(method)){if(body!=null)throw accountValidation("body","归档请求不能包含请求体。");sendAccountResult(exchange,accountApi.archiveAccount(id,revision,accountMutation(exchange,"DELETE",path,Collections.emptyMap())),requestId);return;}
        methodNotAllowed(exchange,requestId,"PUT, DELETE");
    }

    private void sendAccountResult(HttpExchange exchange,AccountApiResult result,String requestId)throws IOException{Map<String,String> h=new LinkedHashMap<>();if(result.getEtag()!=null)h.put("ETag",result.getEtag());if(result.getLocation()!=null)h.put("Location",result.getLocation());sendJson(exchange,result.getStatus(),result.getResponseJson().getBytes(StandardCharsets.UTF_8),requestId,"no-store",h);}
    private JsonNode accountObject(JsonNode v)throws AccountException{if(v==null||!v.isObject())throw accountValidation("body","请求体必须是 JSON 对象。");return v;}
    private AccountPatch parseAccountPatch(JsonNode o,boolean create)throws AccountException{String id=create?accountText(o,"id"):null;String name=accountText(o,"name");String kind=accountText(o,"kind");String opening=accountText(o,"openingOn");String amount=accountText(o,"openingBalance");String currency=accountText(o,"currency");JsonNode cash=o.get("includeInAvailableCash");if(cash==null||!cash.isBoolean())throw accountValidation("includeInAvailableCash","必须是 boolean 值。");if(!"CNY".equals(currency))throw accountValidation("currency","首期只支持 CNY。");LocalDate date;try{date=LocalDate.parse(opening);if(!opening.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();}catch(Exception ex){throw accountValidation("openingOn","必须是 YYYY-MM-DD。");}return new AccountPatch(id,name,kind,date,accountMoney(amount),cash.booleanValue());}
    private Map<String,Object> accountCanonical(JsonNode o,AccountPatch p,boolean create)throws AccountException{Map<String,Object> d=new LinkedHashMap<>();if(create)d.put("id",p.getId());d.put("name",p.getName().trim());d.put("kind",p.getKind());d.put("openingOn",p.getOpeningOn().toString());d.put("openingBalance",formatMoney(p.getOpeningBalanceMinor()));d.put("currency","CNY");d.put("includeInAvailableCash",p.isIncludeInAvailableCash());return d;}
    private String accountText(JsonNode o,String field)throws AccountException{JsonNode v=o.get(field);if(v==null||!v.isTextual())throw accountValidation(field,"必须提供字符串值。");return v.textValue();}
    private long accountMoney(String value)throws AccountException{if(value==null||!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,2})?")||"-0".equals(value)||"-0.0".equals(value)||"-0.00".equals(value))throw accountValidation("openingBalance","必须是规范 CNY 金额字符串。");try{return new BigDecimal(value).movePointRight(2).longValueExact();}catch(ArithmeticException ex){throw accountValidation("openingBalance","金额超出允许范围。");}}
    private LocalDate accountDate(String value)throws AccountException{if(value==null)return null;try{if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();return LocalDate.parse(value);}catch(Exception ex){throw accountValidation("asOf","必须是 YYYY-MM-DD。");}}
    private long parseAccountIfMatch(HttpExchange e)throws AccountException{String v=e.getRequestHeaders().getFirst("If-Match");if(v==null||v.trim().isEmpty())throw new AccountException(428,"PRECONDITION_REQUIRED","需要 If-Match 版本。");if(!v.trim().matches("\\\"[0-9]+\\\""))throw accountValidation("If-Match","必须是强 ETag。");try{return Long.parseLong(v.trim().substring(1,v.trim().length()-1));}catch(NumberFormatException ex){throw accountValidation("If-Match","必须是强 ETag。");}}
    private AccountMutation accountMutation(HttpExchange e,String method,String path,Map<String,Object> body)throws AccountException{String key=e.getRequestHeaders().getFirst("Idempotency-Key");if(key==null||key.trim().isEmpty())throw accountValidation("Idempotency-Key","缺少幂等键。");try{return new AccountMutation(key,method,path,canonicalHash(body));}catch(JsonProcessingException ex){throw accountInternal();}}
    private static AccountException accountValidation(String f,String m){return new AccountException(400,"VALIDATION_FAILED","请求参数无效。",Collections.singletonMap(f,m),Collections.emptyMap());}
    private static AccountException accountInternal(){return new AccountException(500,"INTERNAL_ERROR","本地服务发生内部错误。");}
    private static AccountException accountFromProfile(ProfileException ex){return new AccountException(ex.getHttpStatus(),ex.getCode(),ex.getMessage(),ex.getFieldErrors(),ex.getDetails());}

    private void handleRecords(HttpExchange exchange,String path,JsonNode body,String requestId)throws IOException,RecordException,ProfileException{
        if(recordApi==null)throw new RecordException(423,"RECOVERY_REQUIRED","数据恢复完成前不能操作记录。");String method=exchange.getRequestMethod();
        if("/api/v1/records".equals(path)){
            if("GET".equals(method)){sendRecordResult(exchange,recordApi.listRecords(parseRecordQuery(exchange.getRequestURI())),requestId);return;}
            if("POST".equals(method)){JsonNode o=recordObject(body,true);RecordPatch p=parseRecordPatch(o,true);sendRecordResult(exchange,recordApi.createRecord(p,recordMutation(exchange,"POST",path,recordCanonical(o,p))),requestId);return;}
            methodNotAllowed(exchange,requestId,"GET, POST");return;
        }
        String prefix="/api/v1/records/";if(!path.startsWith(prefix)){sendError(exchange,404,"ROUTE_NOT_FOUND","路由不存在。",requestId,false,Collections.emptyMap());return;}String remaining=path.substring(prefix.length());
        if(remaining.endsWith("/restore")){String id=remaining.substring(0,remaining.length()-"/restore".length());if(id.isEmpty()||id.contains("/"))throw new RecordException(404,"ROUTE_NOT_FOUND","路由不存在。");if(!"POST".equals(method)){methodNotAllowed(exchange,requestId,"POST");return;}requireEmptyObject(body);long revision=parseRecordIfMatch(exchange);sendRecordResult(exchange,recordApi.restoreRecord(id,revision,recordMutation(exchange,"POST",path,Collections.emptyMap())),requestId);return;}
        if(remaining.isEmpty()||remaining.contains("/")){sendError(exchange,404,"ROUTE_NOT_FOUND","路由不存在。",requestId,false,Collections.emptyMap());return;}
        if("GET".equals(method)){Map<String,String> query=singleQuery(exchange.getRequestURI(),setOf("status"));sendRecordResult(exchange,recordApi.getRecord(remaining,query.get("status")==null?"ACTIVE":query.get("status")),requestId);return;}
        long revision=parseRecordIfMatch(exchange);
        if("PUT".equals(method)){JsonNode o=recordObject(body,true);RecordPatch p=parseRecordPatch(o,false);sendRecordResult(exchange,recordApi.replaceRecord(remaining,revision,p,recordMutation(exchange,"PUT",path,recordCanonical(o,p))),requestId);return;}
        if("DELETE".equals(method)){if(body!=null)throw recordValidation("body","归档请求不能包含请求体。");sendRecordResult(exchange,recordApi.trashRecord(remaining,revision,recordMutation(exchange,"DELETE",path,Collections.emptyMap())),requestId);return;}
        methodNotAllowed(exchange,requestId,"GET, PUT, DELETE");
    }
    private void sendRecordResult(HttpExchange e,RecordApiResult r,String requestId)throws IOException{Map<String,String> h=new LinkedHashMap<>();if(r.getEtag()!=null)h.put("ETag",r.getEtag());if(r.getLocation()!=null)h.put("Location",r.getLocation());sendJson(e,r.getStatus(),r.getResponseJson().getBytes(StandardCharsets.UTF_8),requestId,"no-store",h);}
    private JsonNode recordObject(JsonNode v,boolean strict)throws RecordException{if(v==null||!v.isObject())throw recordValidation("body","请求体必须是 JSON 对象。");if(strict){java.util.Iterator<String> names=v.fieldNames();Set<String> allowed=new HashSet<>(Arrays.asList("id","occurredOn","recordType","amount","currency","categoryId","settlement","note"));while(names.hasNext())if(!allowed.contains(names.next()))throw recordValidation("body","包含不支持的字段。");}return v;}
    private RecordPatch parseRecordPatch(JsonNode o,boolean create)throws RecordException{String id=create?recordText(o,"id"):null;String occurred=recordText(o,"occurredOn");String type=recordText(o,"recordType");String amount=recordText(o,"amount");if(!"CNY".equals(recordText(o,"currency")))throw recordValidation("currency","首期只支持 CNY。");String category=recordText(o,"categoryId");JsonNode settlement=o.get("settlement");if(settlement==null||!settlement.isObject())throw recordValidation("settlement","必须是对象。");if(settlement.size()!=3||!settlement.has("mode")||!settlement.has("accountId")||!settlement.has("settlementOn"))throw recordValidation("settlement","字段必须是 mode、accountId、settlementOn。");if(!"PAID_FROM_ACCOUNT".equals(recordText(settlement,"mode")))throw recordValidation("settlement.mode","只支持 PAID_FROM_ACCOUNT。");String account=recordText(settlement,"accountId");String settlementOn=recordText(settlement,"settlementOn");LocalDate occurredDate=parseRecordDate(occurred,"occurredOn"),settlementDate=parseRecordDate(settlementOn,"settlement.settlementOn");RecordType recordType;try{recordType=RecordType.valueOf(type);}catch(Exception ex){throw recordValidation("recordType","记录类型无效。");}JsonNode note=o.get("note");if(note==null||!note.isTextual())throw recordValidation("note","必须提供字符串值。");return new RecordPatch(id,recordType,recordMoney(amount),occurredDate,category,account,settlementDate,note.textValue());}
    private Map<String,Object> recordCanonical(JsonNode o,RecordPatch p){Map<String,Object>d=new LinkedHashMap<>();if(p.getId()!=null)d.put("id",p.getId());d.put("occurredOn",p.getOccurredOn().toString());d.put("recordType",p.getType().name());d.put("amount",formatRecordMoney(p.getAmountMinor()));d.put("currency","CNY");d.put("categoryId",p.getCategoryId());Map<String,Object>s=new LinkedHashMap<>();s.put("mode","PAID_FROM_ACCOUNT");s.put("accountId",p.getAccountId());s.put("settlementOn",p.getSettlementOn().toString());d.put("settlement",s);d.put("note",p.getNote());return d;}
    private String recordText(JsonNode o,String field)throws RecordException{JsonNode v=o.get(field);if(v==null||!v.isTextual())throw recordValidation(field,"必须提供字符串值。");return v.textValue();}
    private LocalDate parseRecordDate(String v,String field)throws RecordException{try{if(!v.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();return LocalDate.parse(v);}catch(Exception ex){throw recordValidation(field,"必须是 YYYY-MM-DD。");}}
    private RecordQuery parseRecordQuery(URI uri)throws RecordException{
        Map<String,List<String>> values=new LinkedHashMap<>();String raw=uri.getRawQuery();Set<String> allowed=setOf("status","occurredFrom","occurredToExclusive","recordType","categoryId","accountId","query","amountMin","amountMax","limit","cursor");
        if(raw!=null&&!raw.isEmpty())for(String pair:raw.split("&",-1)){String[] parts=pair.split("=",2);String key;String value;try{key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8.name());value=URLDecoder.decode(parts.length==2?parts[1]:"",StandardCharsets.UTF_8.name());}catch(Exception ex){throw recordValidation("query","查询参数编码无效。");}if(!allowed.contains(key))throw recordValidation("query","查询参数无效。");values.computeIfAbsent(key,k->new ArrayList<>()).add(value);}
        String status=recordQuerySingle(values,"status");if(status==null)status="ACTIVE";
        LocalDate from=recordQueryDate(recordQuerySingle(values,"occurredFrom"),"occurredFrom");LocalDate to=recordQueryDate(recordQuerySingle(values,"occurredToExclusive"),"occurredToExclusive");
        List<String> types=recordQueryList(values,"recordType");List<String> categories=recordQueryList(values,"categoryId");List<String> accounts=recordQueryList(values,"accountId");String text=recordQuerySingle(values,"query");
        Long min=recordQueryMoney(recordQuerySingle(values,"amountMin"),"amountMin");Long max=recordQueryMoney(recordQuerySingle(values,"amountMax"),"amountMax");int limit=recordQueryLimit(recordQuerySingle(values,"limit"));String cursor=recordQuerySingle(values,"cursor");
        return new RecordQuery(status,from,to,types,categories,accounts,text,min,max,limit,cursor);
    }
    private String recordQuerySingle(Map<String,List<String>> values,String key)throws RecordException{List<String> result=values.get(key);if(result==null||result.isEmpty())return null;if(result.size()!=1)throw recordValidation(key,"查询参数不能重复。");return result.get(0);}
    private List<String> recordQueryList(Map<String,List<String>> values,String key){List<String> result=values.get(key);return result==null?Collections.emptyList():new ArrayList<>(result);}
    private LocalDate recordQueryDate(String value,String field)throws RecordException{return value==null?null:parseRecordDate(value,field);}
    private int recordQueryLimit(String value)throws RecordException{if(value==null)return 50;try{int result=Integer.parseInt(value);if(result<1||result>200)throw new IllegalArgumentException();return result;}catch(Exception ex){throw recordValidation("limit","必须在 1 到 200 之间。");}}
    private Long recordQueryMoney(String value,String field)throws RecordException{if(value==null)return null;if(!value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,2})?"))throw recordValidation(field,"必须是非负规范 CNY 金额字符串。");try{return new BigDecimal(value).movePointRight(2).longValueExact();}catch(Exception ex){throw recordValidation(field,"金额超出允许范围或精度无效。");}}
    private long recordMoney(String value)throws RecordException{
        if(value==null||!value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,2})?"))
            throw recordValidation("amount","必须是大于 0 的规范 CNY 金额字符串。");
        try{
            BigDecimal amount=new BigDecimal(value);
            if(amount.compareTo(BigDecimal.ZERO)<=0)
                throw new IllegalArgumentException();
            return amount.movePointRight(2).longValueExact();
        }catch(IllegalArgumentException ex){
            throw recordValidation("amount","必须是大于 0 的规范 CNY 金额字符串。");
        }catch(Exception ex){
            throw recordValidation("amount","金额超出允许范围或精度无效。");
        }
    }
    private String formatRecordMoney(long minor){return BigDecimal.valueOf(minor,2).setScale(2).toPlainString();}
    private long parseRecordIfMatch(HttpExchange e)throws RecordException{String v=e.getRequestHeaders().getFirst("If-Match");if(v==null||v.trim().isEmpty())throw new RecordException(428,"PRECONDITION_REQUIRED","需要 If-Match 版本。");if(!v.trim().matches("\\\"[0-9]+\\\""))throw recordValidation("If-Match","必须是强 ETag。");try{return Long.parseLong(v.trim().substring(1,v.trim().length()-1));}catch(Exception ex){throw recordValidation("If-Match","必须是强 ETag。");}}
    private RecordMutation recordMutation(HttpExchange e,String method,String path,Map<String,Object> body)throws RecordException{String key=e.getRequestHeaders().getFirst("Idempotency-Key");if(key==null||key.trim().isEmpty())throw recordValidation("Idempotency-Key","缺少幂等键。");try{return new RecordMutation(key,method,path,canonicalHash(body));}catch(JsonProcessingException ex){throw recordInternal();}}
    private static RecordException recordValidation(String f,String m){return new RecordException(400,"VALIDATION_FAILED","请求参数无效。",Collections.singletonMap(f,m),Collections.emptyMap());}
    private static RecordException recordInternal(){return new RecordException(500,"INTERNAL_ERROR","本地服务发生内部错误。");}

    private void sendProfileResult(HttpExchange exchange, ProfileApiResult result, String requestId) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (result.getEtag() != null) {
            headers.put("ETag", result.getEtag());
        }
        if (result.getLocation() != null) {
            headers.put("Location", result.getLocation());
        }
        sendJson(exchange, result.getStatus(), result.getResponseJson().getBytes(StandardCharsets.UTF_8), requestId,
                "no-store", headers);
    }

    private void sendSettingsResult(HttpExchange exchange, SettingsApiResult result, String requestId) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (result.getEtag() != null) {
            headers.put("ETag", result.getEtag());
        }
        sendJson(exchange, result.getStatus(), result.getResponseJson().getBytes(StandardCharsets.UTF_8), requestId,
                "no-store", headers);
    }

    private ProfileMutation mutation(HttpExchange exchange, String method, String canonicalPath,
            Map<String, Object> canonicalBody) throws ProfileException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.trim().isEmpty()) {
            throw validation("Idempotency-Key", "缺少幂等键。");
        }
        try {
            return new ProfileMutation(key, method, canonicalPath, canonicalHash(canonicalBody));
        } catch (JsonProcessingException ex) {
            throw new ProfileException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
        }
    }

    private SettingsMutation settingsMutation(HttpExchange exchange, Map<String, Object> canonicalBody)
            throws SettingsException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.trim().isEmpty()) {
            throw settingsValidation("Idempotency-Key", "缺少幂等键。");
        }
        try {
            return new SettingsMutation(key, "PATCH", "/api/v1/settings", canonicalHash(canonicalBody));
        } catch (JsonProcessingException ex) {
            throw new SettingsException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
        }
    }

    private String canonicalHash(Map<String, Object> body) throws JsonProcessingException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private JsonNode requireObject(JsonNode value) throws ProfileException {
        if (value == null || !value.isObject()) {
            throw validation("body", "请求体必须是 JSON 对象。");
        }
        return value;
    }

    private SettingsRequest parseSettingsRequest(JsonNode value) throws SettingsException {
        if (value == null || !value.isObject()) {
            throw settingsValidation("body", "请求体必须是 JSON 对象。");
        }
        Boolean notificationsEnabled = null;
        Boolean autoBackupEnabled = null;
        Integer autoBackupIntervalDays = null;
        Integer autoBackupRetentionCount = null;
        String lastSettingsSection = null;
        Long safetyBufferMinor = null;
        Boolean hideAllAmounts = null;
        String themeName = null;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode fieldValue = field.getValue();
            if ("notificationsEnabled".equals(name)) {
                notificationsEnabled = settingsBoolean(fieldValue, name);
            } else if ("autoBackupEnabled".equals(name)) {
                autoBackupEnabled = settingsBoolean(fieldValue, name);
            } else if ("autoBackupIntervalDays".equals(name)) {
                autoBackupIntervalDays = settingsInteger(fieldValue, name, 1, 365);
            } else if ("autoBackupRetentionCount".equals(name)) {
                autoBackupRetentionCount = settingsInteger(fieldValue, name, 1, 100);
            } else if ("lastSettingsSection".equals(name)) {
                lastSettingsSection = settingsText(fieldValue, name);
                if (!Arrays.asList("GENERAL", "PROFILES", "CATEGORIES", "ACCOUNTS").contains(lastSettingsSection)) {
                    throw settingsValidation(name, "不是受支持的设置页。");
                }
            } else if ("safetyBuffer".equals(name)) {
                safetyBufferMinor = settingsMoney(fieldValue);
            } else if ("hideAllAmounts".equals(name)) {
                hideAllAmounts = settingsBoolean(fieldValue, name);
            } else if ("themeName".equals(name)) {
                themeName = settingsText(fieldValue, name);
                if (!Arrays.asList("WARM_COPPER", "GRAPHITE", "DEEP_SEA_BLUE").contains(themeName)) {
                    throw settingsValidation(name, "必须是受支持的内置主题。");
                }
            } else if ("currency".equals(name) || "lastAutoBackupAt".equals(name)
                    || "revision".equals(name) || "updatedAt".equals(name) || "customThemeCss".equals(name)) {
                throw settingsValidation(name, "该字段只读，不能修改。");
            }
        }
        SettingsPatch patch = new SettingsPatch(notificationsEnabled, autoBackupEnabled, autoBackupIntervalDays,
                autoBackupRetentionCount, lastSettingsSection, safetyBufferMinor, hideAllAmounts, themeName);
        if (patch.isEmpty()) {
            throw settingsValidation("body", "至少需要一个可写设置字段。");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        if (notificationsEnabled != null) {
            canonical.put("notificationsEnabled", notificationsEnabled);
        }
        if (autoBackupEnabled != null) {
            canonical.put("autoBackupEnabled", autoBackupEnabled);
        }
        if (autoBackupIntervalDays != null) {
            canonical.put("autoBackupIntervalDays", autoBackupIntervalDays);
        }
        if (autoBackupRetentionCount != null) {
            canonical.put("autoBackupRetentionCount", autoBackupRetentionCount);
        }
        if (lastSettingsSection != null) {
            canonical.put("lastSettingsSection", lastSettingsSection);
        }
        if (safetyBufferMinor != null) {
            Map<String, Object> money = new LinkedHashMap<>();
            money.put("amount", formatMoney(safetyBufferMinor));
            money.put("currency", "CNY");
            canonical.put("safetyBuffer", money);
        }
        if (hideAllAmounts != null) {
            canonical.put("hideAllAmounts", hideAllAmounts);
        }
        if (themeName != null) {
            canonical.put("themeName", themeName);
        }
        return new SettingsRequest(patch, canonical);
    }

    private static boolean settingsBoolean(JsonNode value, String field) throws SettingsException {
        if (value == null || !value.isBoolean()) {
            throw settingsValidation(field, "必须是 boolean 值。");
        }
        return value.booleanValue();
    }

    private static int settingsInteger(JsonNode value, String field, int minimum, int maximum)
            throws SettingsException {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw settingsValidation(field, "必须是 " + minimum + " 到 " + maximum + " 的整数。");
        }
        return value.intValue();
    }

    private static String settingsText(JsonNode value, String field) throws SettingsException {
        if (value == null || !value.isTextual()) {
            throw settingsValidation(field, "必须提供字符串值。");
        }
        return value.textValue();
    }

    private static long settingsMoney(JsonNode value) throws SettingsException {
        if (value == null || !value.isObject()) {
            throw settingsValidation("safetyBuffer", "必须是金额对象。");
        }
        JsonNode amount = value.get("amount");
        JsonNode currency = value.get("currency");
        if (amount == null || !amount.isTextual()
                || !amount.textValue().matches("(0|[1-9][0-9]*)(\\.[0-9]{1,2})?")) {
            throw settingsValidation("safetyBuffer.amount", "必须是非负规范金额字符串。");
        }
        if (currency == null || !currency.isTextual() || !"CNY".equals(currency.textValue())) {
            throw settingsValidation("safetyBuffer.currency", "首期只支持 CNY。");
        }
        try {
            return new BigDecimal(amount.textValue()).movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw settingsValidation("safetyBuffer.amount", "金额超出允许范围。");
        }
    }

    private static String formatMoney(long minor) {
        return BigDecimal.valueOf(minor, 2).setScale(2).toPlainString();
    }

    private void requireEmptyObject(JsonNode value) throws ProfileException {
        JsonNode object = requireObject(value);
        if (object.size() != 0) {
            throw validation("body", "该请求只接受空对象。");
        }
    }

    private String requiredText(JsonNode object, String field) throws ProfileException {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field, "必须提供字符串值。");
        }
        return value.textValue();
    }

    private long parseIfMatch(HttpExchange exchange) throws ProfileException {
        String value = exchange.getRequestHeaders().getFirst("If-Match");
        if (value == null || value.trim().isEmpty()) {
            throw new ProfileException(428, "PRECONDITION_REQUIRED", "需要 If-Match 版本。");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 3 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) {
            throw validation("If-Match", "必须是强 ETag。");
        }
        try {
            long revision = Long.parseLong(trimmed.substring(1, trimmed.length() - 1));
            if (revision < 0L) {
                throw validation("If-Match", "必须是非负版本。");
            }
            return revision;
        } catch (NumberFormatException ex) {
            throw validation("If-Match", "必须是强 ETag。");
        }
    }

    private long parseSettingsIfMatch(HttpExchange exchange) throws SettingsException {
        String value = exchange.getRequestHeaders().getFirst("If-Match");
        if (value == null || value.trim().isEmpty()) {
            throw new SettingsException(428, "PRECONDITION_REQUIRED", "需要 If-Match 版本。");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 3 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) {
            throw settingsValidation("If-Match", "必须是强 ETag。");
        }
        try {
            long revision = Long.parseLong(trimmed.substring(1, trimmed.length() - 1));
            if (revision < 0L) {
                throw settingsValidation("If-Match", "必须是非负版本。");
            }
            return revision;
        } catch (NumberFormatException ex) {
            throw settingsValidation("If-Match", "必须是强 ETag。");
        }
    }

    private Map<String, String> singleQuery(URI uri, Set<String> allowed) throws ProfileException {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String key = decodeQuery(parts[0]);
            String value = parts.length == 2 ? decodeQuery(parts[1]) : "";
            if (!allowed.contains(key) || values.put(key, value) != null) {
                throw validation("query", "查询参数无效。");
            }
        }
        return values;
    }

    private static String decodeQuery(String value) throws ProfileException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            throw validation("query", "查询参数编码无效。");
        }
    }

    private static boolean parseBoolean(String value, String field, boolean defaultValue) throws ProfileException {
        if (value == null) {
            return defaultValue;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw validation(field, "必须是 true 或 false。");
    }

    private static int parseLimit(String value) throws ProfileException {
        if (value == null) {
            return 50;
        }
        if (!value.matches("[1-9][0-9]*")) {
            throw validation("limit", "必须是 1 到 200 的整数。");
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > 200) {
                throw validation("limit", "必须是 1 到 200 的整数。");
            }
            return limit;
        } catch (NumberFormatException ex) {
            throw validation("limit", "必须是 1 到 200 的整数。");
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String requestId, String allow) {
        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不被允许。", requestId, false,
                Collections.singletonMap("Allow", allow));
    }

    private static ProfileException validation(String field, String message) {
        return new ProfileException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static SettingsException settingsValidation(String field, String message) {
        return new SettingsException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private void handleStatic(HttpExchange exchange, String rawPath, String decodedPath) throws IOException {
        if (!networkAllowed(exchange, false)) {
            sendError(exchange, 403, "REQUEST_ORIGIN_FORBIDDEN", "请求来源不被允许。", requestIdOrNew(exchange), false,
                    Collections.emptyMap());
            return;
        }
        String method = exchange.getRequestMethod();
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不被允许。", requestIdOrNew(exchange), false,
                    Collections.singletonMap("Allow", "GET, HEAD"));
            return;
        }
        StaticResourceManifest.Resource resource = resources.open(rawPath, decodedPath);
        if (resource == null) {
            sendError(exchange, 404, "ROUTE_NOT_FOUND", "资源不存在。", requestIdOrNew(exchange), false,
                    Collections.emptyMap());
            return;
        }
        byte[] content = resource.getContent();
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", resource.getContentType());
        headers.set("Cache-Control", resource.getCacheControl());
        headers.set("Content-Length", String.valueOf(content.length));
        if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private ParsedBody validateBody(HttpExchange exchange, String requestId) throws IOException {
        if (!isMutation(exchange.getRequestMethod())) {
            return new ParsedBody(null);
        }
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        long declaredLength = -1L;
        if (lengthHeader != null) {
            try {
                declaredLength = Long.parseLong(lengthHeader);
            } catch (NumberFormatException ex) {
                sendError(exchange, 400, "VALIDATION_FAILED", "请求长度无效。", requestId, false,
                        Collections.emptyMap());
                return null;
            }
            if (declaredLength < 0L) {
                sendError(exchange, 400, "VALIDATION_FAILED", "请求长度无效。", requestId, false,
                        Collections.emptyMap());
                return null;
            }
            if (declaredLength > MAX_JSON_BODY_BYTES) {
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", "请求体超过大小限制。", requestId, false,
                        Collections.emptyMap());
                return null;
            }
        }
        if (declaredLength == 0L) {
            return new ParsedBody(null);
        }
        byte[] body = readAtMost(exchange.getRequestBody(), MAX_JSON_BODY_BYTES + 1);
        if (body.length > MAX_JSON_BODY_BYTES) {
            sendError(exchange, 413, "PAYLOAD_TOO_LARGE", "请求体超过大小限制。", requestId, false,
                    Collections.emptyMap());
            return null;
        }
        if (body.length == 0) {
            return new ParsedBody(null);
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "请求体必须使用 JSON。", requestId, false,
                    Collections.emptyMap());
            return null;
        }
        try {
            return new ParsedBody(objectMapper.readTree(body));
        } catch (JsonProcessingException ex) {
            sendError(exchange, 400, "INVALID_JSON", "请求体不是有效 JSON。", requestId, false,
                    Collections.emptyMap());
            return null;
        }
    }

    private boolean networkAllowed(HttpExchange exchange, boolean mutation) {
        String expectedHost = address().getHostString() + ":" + address().getPort();
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!expectedHost.equals(host)) {
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null ? !mutation : origin.equals(origin());
    }

    private String validRequestId(HttpExchange exchange) {
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (requestId != null && REQUEST_ID_FORMAT.matcher(requestId).matches()) {
            try {
                UUID parsed = UUID.fromString(requestId);
                if (parsed.version() == 4 && parsed.variant() == 2) {
                    return requestId;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to the stable error below.
            }
        }
        String generated = UUID.randomUUID().toString();
        sendError(exchange, 400, "INVALID_REQUEST_ID", "需要有效的 UUID v4 请求标识。", generated, false,
                Collections.emptyMap());
        return null;
    }

    private String requestIdOrNew(HttpExchange exchange) {
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (requestId != null && REQUEST_ID_FORMAT.matcher(requestId).matches()) {
            try {
                UUID parsed = UUID.fromString(requestId);
                if (parsed.version() == 4 && parsed.variant() == 2) {
                    return requestId;
                }
            } catch (IllegalArgumentException ignored) {
                // Generate a safe correlation id for malformed request ids.
            }
        }
        return UUID.randomUUID().toString();
    }

    private void sendError(
            HttpExchange exchange,
            int status,
            String code,
            String message,
            String requestId,
            boolean retryable,
            Map<String, String> extraHeaders) {
        sendError(exchange, status, code, message, requestId, retryable, Collections.emptyMap(),
                Collections.emptyMap(), extraHeaders);
    }

    private void sendError(
            HttpExchange exchange,
            int status,
            String code,
            String message,
            String requestId,
            boolean retryable,
            Map<String, String> fieldErrors,
            Map<String, Object> details,
            Map<String, String> extraHeaders) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("requestId", requestId);
        error.put("fieldErrors", fieldErrors);
        error.put("retryable", retryable);
        error.put("recoverySuggestion", "");
        error.put("details", details);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", error);
        try {
            sendJson(exchange, status, json(response), requestId, "no-store", extraHeaders);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private void sendJson(
            HttpExchange exchange,
            int status,
            byte[] body,
            String requestId,
            String cacheControl,
            Map<String, String> extraHeaders) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", cacheControl);
        headers.set("Content-Length", String.valueOf(body.length));
        headers.set("LedgerX-Api-Version", API_VERSION);
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            headers.set(entry.getKey(), entry.getValue());
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private byte[] json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(value);
    }

    private static final class ParsedBody {
        private final JsonNode json;

        private ParsedBody(JsonNode json) {
            this.json = json;
        }
    }

    private static final class SettingsRequest {
        private final SettingsPatch patch;
        private final Map<String, Object> canonicalBody;

        private SettingsRequest(SettingsPatch patch, Map<String, Object> canonicalBody) {
            this.patch = patch;
            this.canonicalBody = canonicalBody;
        }
    }

    private void writeReadinessLine() {
        if (readinessWritten.compareAndSet(false, true)) {
            readinessOut.println(READY_PREFIX + "{\"port\":" + address().getPort() + ",\"protocol\":\"1\"}");
            readinessOut.flush();
        }
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static byte[] readAtMost(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                return new byte[maxBytes + 1];
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void validateBindAddress(InetSocketAddress address) {
        if (address == null || address.getAddress() == null || address.getPort() < 0 || address.getPort() > 65535
                || !"127.0.0.1".equals(address.getHostString()) || !isIpv4Loopback(address.getAddress())) {
            throw new IllegalArgumentException("server must bind IPv4 loopback");
        }
    }

    private static void validateBoundAddress(InetSocketAddress address) {
        if (address == null || address.getPort() < 1 || !isIpv4Loopback(address.getAddress())) {
            throw new IllegalStateException("server did not bind IPv4 loopback");
        }
    }

    private static boolean isIpv4Loopback(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() && bytes != null && bytes.length == 4
                && (bytes[0] & 0xff) == 127 && (bytes[1] & 0xff) == 0
                && (bytes[2] & 0xff) == 0 && (bytes[3] & 0xff) == 1;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
