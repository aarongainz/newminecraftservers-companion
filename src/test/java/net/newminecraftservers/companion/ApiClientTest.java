package net.newminecraftservers.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiClientTest {
    private HttpServer server;
    private ApiClient client;
    private final AtomicInteger detailCalls = new AtomicInteger();
    private CountDownLatch slowStarted;
    private CountDownLatch slowRelease;

    @BeforeEach
    void startServer() throws IOException {
        slowStarted = new CountDownLatch(1);
        slowRelease = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/paper/link/start", exchange -> respond(exchange, 200, """
            {"data":{"linkId":"11111111-1111-4111-8111-111111111111","secret":"abcdefghijklmnopqrstuvwxyzABCDEFG123456789","challengeCode":"NMS-ABC234","expiresAt":"2026-09-18T12:02:00.000Z","address":"play.example.net"}}
            """));
        server.createContext("/api/v1/servers/alpha", exchange -> {
            detailCalls.incrementAndGet();
            respond(exchange, 200, serverJson());
        });
        server.createContext("/api/v1/servers/failure", exchange -> respond(exchange, 503, """
            {"error":{"code":"probe_unavailable","message":"The public Java server could not be reached"}}
            """));
        server.createContext("/api/v1/servers/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/api/v1/servers/alpha");
            respond(exchange, 302, "{\"error\":{\"code\":\"redirect\",\"message\":\"Redirect refused\"}}");
        });
        server.createContext("/api/v1/servers/malformed", exchange -> respond(exchange, 200, "not-json"));
        server.createContext("/api/v1/servers/missing", exchange -> respond(exchange, 404, """
            {"error":{"code":"not_found","message":"Server not found"}}
            """));
        server.createContext("/api/v1/servers/slow", exchange -> {
            slowStarted.countDown();
            try {
                slowRelease.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, serverJson());
        });
        server.createContext("/api/v1/servers/alpha/history", exchange -> {
            if (!"Bearer secret-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{\"error\":{\"code\":\"unauthorized\",\"message\":\"Token required\"}}");
                return;
            }
            respond(exchange, 200, """
                {"data":{"range":"90d","resolution":"day","points":[],"summary":{"checks":10,"daysCovered":2},"versions":[],"motdChanges":0}}
                """);
        });
        server.createContext("/api/v1/paper/claim/check", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (exchange.getRequestHeaders().getFirst("Authorization") != null) {
                respond(exchange, 400, "{\"error\":{\"code\":\"unexpected_token\",\"message\":\"No token expected\"}}");
                return;
            }
            respond(exchange, 200, body.contains("NMS-7K3QPX9A")
                ? "{\"data\":{\"status\":\"verified\",\"server\":{\"name\":\"Alpha\",\"slug\":\"alpha\"},\"message\":\"Verified.\"}}"
                : "{\"data\":{\"status\":\"pending\",\"server\":{\"name\":\"Alpha\",\"slug\":\"alpha\"},\"checkedAddress\":\"play.example.net\",\"observedMotd\":\"Welcome\",\"message\":\"Not visible yet\"}}");
        });
        server.createContext("/api/v1/paper/unlink", exchange -> {
            boolean authorized = "POST".equals(exchange.getRequestMethod())
                && "Bearer secret-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, authorized ? 200 : 401, authorized ? "{\"data\":{\"revoked\":true}}" :
                "{\"error\":{\"code\":\"unauthorized\",\"message\":\"Token required\"}}");
        });
        server.start();
        client = new ApiClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/"),
            2,
            60
        );
    }

    @AfterEach
    void stopServer() {
        slowRelease.countDown();
        server.stop(0);
    }

    @Test
    void productionOriginIsCompiledIn() {
        assertEquals("https://newminecraftservers.net/api/v1/", ApiClient.productionBaseUri().toString());
    }

    @Test
    void decodesLinkResponsesAndCachesPublicReads() {
        ApiModels.LinkStart start = client.startLink("play.example.net").join();
        assertEquals("NMS-ABC234", start.challengeCode());
        assertEquals("play.example.net", start.address());

        assertEquals("Alpha", client.serverBySlug("alpha").join().name());
        assertEquals("Alpha", client.serverBySlug("alpha").join().name());
        assertEquals(1, detailCalls.get());
    }

    @Test
    void exposesSafeApiErrors() {
        CompletionException wrapped = assertThrows(CompletionException.class, () ->
            invokeFailure().join()
        );
        assertTrue(wrapped.getCause() instanceof ApiException);
        ApiException error = (ApiException) wrapped.getCause();
        assertEquals(503, error.status());
        assertEquals("probe_unavailable", error.code());
        assertEquals("The public Java server could not be reached", error.getMessage());
    }

    @Test
    void rejectsMalformedAndMissingResponsesWithoutLeakingBodies() {
        CompletionException malformed = assertThrows(CompletionException.class, () ->
            client.serverBySlug("malformed").join()
        );
        assertTrue(malformed.getCause() instanceof ApiException);
        assertEquals("invalid_response", ((ApiException) malformed.getCause()).code());

        CompletionException missing = assertThrows(CompletionException.class, () ->
            client.serverBySlug("missing").join()
        );
        assertTrue(missing.getCause() instanceof ApiException);
        ApiException error = (ApiException) missing.getCause();
        assertEquals(404, error.status());
        assertEquals("not_found", error.code());
        assertEquals("Server not found", error.getMessage());
    }

    @Test
    void refusesRedirectsAwayFromTheCompiledApiOrigin() {
        CompletionException redirected = assertThrows(CompletionException.class, () ->
            client.serverBySlug("redirect").join()
        );
        assertTrue(redirected.getCause() instanceof ApiException);
        assertEquals(302, ((ApiException) redirected.getCause()).status());
        assertEquals(0, detailCalls.get());
    }

    @Test
    void networkCallsAreAsynchronousAndBoundedByTimeout() throws InterruptedException {
        ApiClient shortTimeout = new ApiClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/"),
            1,
            0
        );
        var future = shortTimeout.serverBySlug("slow");
        assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
        assertTrue(!future.isDone());
        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        slowRelease.countDown();
    }

    @Test
    void sendsServerScopedCredentialsForHistoryAndUnlink() {
        ApiModels.History history = client.history("alpha", "90d", "secret-token").join();
        assertEquals(2, history.summary().daysCovered());
        client.unlink("secret-token").join();
    }

    @Test
    void checksClaimCodesWithoutACredential() {
        ApiModels.ClaimCheck pending = client.claimCheck("NMS-22222222").join();
        assertEquals("pending", pending.status());
        assertEquals("Welcome", pending.observedMotd());
        assertEquals("play.example.net", pending.checkedAddress());
        ApiModels.ClaimCheck verified = client.claimCheck("NMS-7K3QPX9A").join();
        assertEquals("verified", verified.status());
        assertEquals("alpha", verified.server().slug());
    }

    @Test
    void acceptsOnlyLoopbackApiOverrides() {
        try {
            System.setProperty("newminecraftservers.apiBase", "http://127.0.0.1:3000/api/v1");
            assertEquals("http://127.0.0.1:3000/api/v1/", ApiClient.configuredBaseUri().toString());
            System.setProperty("newminecraftservers.apiBase", "https://evil.example/api/v1/");
            assertThrows(IllegalArgumentException.class, ApiClient::configuredBaseUri);
            System.setProperty("newminecraftservers.apiBase", "http://10.0.0.5/api/v1/");
            assertThrows(IllegalArgumentException.class, ApiClient::configuredBaseUri);
        } finally {
            System.clearProperty("newminecraftservers.apiBase");
        }
        assertEquals(ApiClient.productionBaseUri(), ApiClient.configuredBaseUri());
    }

    @Test
    void normalizesClaimCodesTypedInChat() {
        assertEquals("NMS-7K3QPX9A", NewMinecraftServersCompanion.normalizeClaimCode("nms-7k3qpx9a"));
        assertEquals("NMS-7K3QPX9A", NewMinecraftServersCompanion.normalizeClaimCode("7K3Q PX9A"));
        assertEquals(null, NewMinecraftServersCompanion.normalizeClaimCode("NMS-ABC234"));
        assertEquals(null, NewMinecraftServersCompanion.normalizeClaimCode("NMS-7K3QPX9I"));
    }

    private java.util.concurrent.CompletableFuture<Object> invokeFailure() {
        // Reuse the package-private client parser through a public method pointed at an error context.
        return new ApiClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/"),
            2,
            0
        ).serverBySlug("failure").thenApply(value -> value);
    }

    private static String serverJson() {
        return """
            {"data":{"id":"server-alpha","slug":"alpha","name":"Alpha","classification":{"genreIds":["survival"],"tags":["survival"]},"primaryConnection":{"id":"conn-alpha","edition":"java","host":"play.example.net","port":25565,"latest":{"outcome":"online","observedAt":"2026-09-18T12:00:00.000Z","playersOnline":10,"playersMax":100}},"primarySnapshot":{"connectionId":"conn-alpha","edition":"java","outcome":"online","observedAt":"2026-09-18T12:00:00.000Z","playersOnline":10,"playersMax":100},"links":{},"firstSeenByUsAt":"2026-09-18T00:00:00.000Z","lastSeenByUsAt":"2026-09-18T12:00:00.000Z"}}
            """;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
