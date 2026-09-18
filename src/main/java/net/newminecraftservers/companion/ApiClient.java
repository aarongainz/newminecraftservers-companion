package net.newminecraftservers.companion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class ApiClient {
    private static final URI PRODUCTION_API = URI.create("https://newminecraftservers.net/api/v1/");
    private static final String USER_AGENT = "NewMinecraftServersCompanion/0.2.0 (+https://newminecraftservers.net)";

    private final URI baseUri;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final Duration timeout;
    private final long cacheMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object value, long expiresAt) {}

    static ApiClient production(int timeoutSeconds, int cacheSeconds) {
        return new ApiClient(configuredBaseUri(), timeoutSeconds, cacheSeconds);
    }

    /**
     * The production origin, unless the JVM was started with
     * -Dnewminecraftservers.apiBase=http://127.0.0.1:PORT/api/v1/ for local testing. Only loopback
     * origins are accepted, so a config file or another plugin cannot redirect requests elsewhere.
     */
    static URI configuredBaseUri() {
        String override = System.getProperty("newminecraftservers.apiBase", "").trim();
        if (override.isEmpty()) return PRODUCTION_API;
        URI uri = URI.create(override.endsWith("/") ? override : override + "/");
        String host = uri.getHost();
        boolean loopback = host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"));
        if (!loopback || !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("newminecraftservers.apiBase must be an http://127.0.0.1 or http://localhost URL");
        }
        return uri;
    }

    static URI productionBaseUri() {
        return PRODUCTION_API;
    }

    ApiClient(URI baseUri, int timeoutSeconds, int cacheSeconds) {
        this.baseUri = Objects.requireNonNull(baseUri);
        if (!baseUri.isAbsolute() || !baseUri.toString().endsWith("/")) {
            throw new IllegalArgumentException("API base URI must be absolute and end with /");
        }
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, 30)));
        this.cacheMillis = Math.max(0, Math.min(cacheSeconds, 300)) * 1_000L;
        this.http = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    CompletableFuture<ApiModels.LinkStart> startLink(String address) {
        JsonObject body = new JsonObject();
        body.addProperty("address", address);
        return request("POST", "paper/link/start", body, null, ApiModels.LinkStart.class);
    }

    CompletableFuture<ApiModels.LinkVerified> verifyLink(String linkId, String secret) {
        JsonObject body = new JsonObject();
        body.addProperty("linkId", linkId);
        body.addProperty("secret", secret);
        return request("POST", "paper/link/verify", body, null, ApiModels.LinkVerified.class)
            .thenApply(result -> {
                cache.clear();
                return result;
            });
    }

    CompletableFuture<ApiModels.ServerDetail> configuredServer(String slug, String address) {
        if (slug != null && !slug.isBlank()) return serverBySlug(slug);
        if (address != null && !address.isBlank()) return lookupAddress(address);
        return CompletableFuture.failedFuture(new IllegalStateException("Server is not linked"));
    }

    CompletableFuture<ApiModels.ServerDetail> lookup(String reference) {
        return reference.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
            ? serverBySlug(reference)
            : lookupAddress(reference);
    }

    CompletableFuture<ApiModels.ServerDetail> serverBySlug(String slug) {
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid listing slug"));
        }
        return cached("server:" + slug, () -> request(
            "GET", "servers/" + slug, null, null, ApiModels.ServerDetail.class
        ));
    }

    CompletableFuture<ApiModels.ServerDetail> lookupAddress(String address) {
        return cached("address:" + address, () -> request(
            "GET",
            "servers/lookup?address=" + encode(address),
            null,
            null,
            ApiModels.ServerDetail.class
        ));
    }

    CompletableFuture<ApiModels.History> history(String slug, String range, String token) {
        if (!List.of("24h", "7d", "30d", "90d").contains(range)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Range must be 24h, 7d, 30d, or 90d"));
        }
        return cached("history:" + slug + ":" + range, () -> request(
            "GET",
            "servers/" + slug + "/history?range=" + range,
            null,
            token == null || token.isBlank() ? null : token,
            ApiModels.History.class
        ));
    }

    CompletableFuture<ApiModels.ClaimCheck> claimCheck(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return request("POST", "paper/claim/check", body, null, ApiModels.ClaimCheck.class)
            .thenApply(result -> {
                if (!"pending".equals(result.status())) cache.clear();
                return result;
            });
    }

    CompletableFuture<Void> unlink(String token) {
        return request("POST", "paper/unlink", new JsonObject(), token, JsonObject.class)
            .thenApply(ignored -> {
                cache.clear();
                return null;
            });
    }

    private <T> CompletableFuture<T> cached(String key, Supplier<CompletableFuture<T>> loader) {
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAt > now) {
            @SuppressWarnings("unchecked") T value = (T) entry.value;
            return CompletableFuture.completedFuture(value);
        }
        return loader.get().thenApply(value -> {
            if (cacheMillis > 0) cache.put(key, new CacheEntry(value, System.currentTimeMillis() + cacheMillis));
            return value;
        });
    }

    private <T> CompletableFuture<T> request(
        String method,
        String path,
        JsonObject body,
        String token,
        Class<T> type
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT);
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        }
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> decode(response, type));
    }

    private <T> T decode(HttpResponse<String> response, Class<T> type) {
        JsonObject root;
        try {
            root = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new ApiException(response.statusCode(), "invalid_response", "The API returned an invalid response");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            JsonObject error = root.has("error") && root.get("error").isJsonObject()
                ? root.getAsJsonObject("error") : new JsonObject();
            String code = error.has("code") ? error.get("code").getAsString() : "request_failed";
            String message = error.has("message") ? error.get("message").getAsString() : "The API request failed";
            throw new ApiException(response.statusCode(), code, message);
        }
        if (!root.has("data")) {
            throw new ApiException(response.statusCode(), "invalid_response", "The API response did not contain data");
        }
        return gson.fromJson(root.get("data"), type);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
