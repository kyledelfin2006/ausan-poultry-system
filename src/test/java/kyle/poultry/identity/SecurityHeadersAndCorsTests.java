package kyle.poultry.identity;

import static kyle.poultry.support.HttpApiClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.support.HttpApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * End-to-end tests over real HTTP of the browser-facing protections: CORS (allowed and refused origins), security
 * response headers, the request id header, and who may read operational (actuator) endpoints. The allowed origins
 * come from the default {@code application.yaml} (local frontend dev servers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SecurityHeadersAndCorsTests {
    static final String ALLOWED = "http://localhost:5173";
    static final String EVIL = "https://evil.example.com";

    @LocalServerPort int port;

    /** {@return a client for the running application} */
    HttpApiClient api() { return new HttpApiClient(port); }

    /**
     * Reads a response header, case-insensitively.
     *
     * @param r    the response
     * @param name the header name
     * @return the first value, or null if absent
     */
    private static String header(HttpResponse<?> r, String name) { return r.headers().firstValue(name).orElse(null); }

    /** A browser preflight from an allowed origin is answered without a token and names exactly what is allowed. */
    @Test
    void preflightFromAnAllowedOriginSucceedsWithoutCredentials() throws Exception {
        var r = api().call("OPTIONS", "/api/deliveries", null, null,
                "Origin", ALLOWED, "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type,idempotency-key");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(header(r, "Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(header(r, "Access-Control-Allow-Methods")).contains("POST", "PATCH");
        assertThat(header(r, "Access-Control-Allow-Headers").toLowerCase()).contains("authorization", "idempotency-key");
        // Bearer tokens are sent explicitly, so cookies/credentials mode must stay off.
        assertThat(header(r, "Access-Control-Allow-Credentials")).isNull();
        assertThat(header(r, "Access-Control-Max-Age")).isEqualTo("3600");
    }

    /** A preflight from any other origin is refused, and no allow headers leak. */
    @Test
    void preflightFromAnUnknownOriginIsRefused() throws Exception {
        var r = api().call("OPTIONS", "/api/deliveries", null, null,
                "Origin", EVIL, "Access-Control-Request-Method", "POST");

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(header(r, "Access-Control-Allow-Origin")).isNull();
    }

    /** A real call from an unknown origin is refused even with a valid token: CORS is a browser rule, but it is enforced. */
    @Test
    void actualRequestsFromAnUnknownOriginAreRefusedAndAllowedOnesExposeTheRightHeaders() throws Exception {
        var api = api();
        String token = api.ownerToken();

        var evil = api.call("GET", "/api/products", token, null, "Origin", EVIL);
        assertThat(evil.statusCode()).isEqualTo(403);
        assertThat(evil.body()).doesNotContain("Chicken");

        var ok = api.call("GET", "/api/products", token, null, "Origin", ALLOWED);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(header(ok, "Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(header(ok, "Access-Control-Expose-Headers")).contains("X-Request-Id", "Retry-After");

        // Without an Origin header (curl, server-to-server) no CORS headers are added.
        assertThat(header(api.call("GET", "/api/products", token, null), "Access-Control-Allow-Origin")).isNull();
    }

    /** Responses carry the hardening headers, on successes and on 401s alike. */
    @Test
    void securityHeadersArePresentOnEveryResponse() throws Exception {
        var api = api();
        for (var r : new HttpResponse<?>[]{api.call("GET", "/api/products", api.ownerToken(), null),
                api.call("GET", "/api/products", null, null)}) {
            assertThat(header(r, "X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(header(r, "X-Frame-Options")).isEqualTo("DENY");
            assertThat(header(r, "Content-Security-Policy")).contains("default-src 'none'").contains("frame-ancestors 'none'");
            assertThat(header(r, "Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(header(r, "Permissions-Policy")).contains("camera=()", "geolocation=()");
            // Responses hold business data and must never be stored by a shared cache.
            assertThat(header(r, "Cache-Control")).contains("no-store");
        }
    }

    /** Every response has an X-Request-Id; a safe one from the caller is echoed, an unsafe one is replaced. */
    @Test
    void requestIdsAreGeneratedEchoedAndSanitised() throws Exception {
        var api = api();

        // Even a 401 (rejected before any controller) carries an id, because the filter runs first.
        var anonymous = api.call("GET", "/api/products", null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(UUID.fromString(header(anonymous, "X-Request-Id"))).isNotNull();

        assertThat(header(api.call("GET", "/api/products", null, null, "X-Request-Id", "web-123.abc"), "X-Request-Id"))
                .isEqualTo("web-123.abc");

        String unsafe = "bad id<script>";
        String replaced = header(api.call("GET", "/api/products", null, null, "X-Request-Id", unsafe), "X-Request-Id");
        assertThat(replaced).isNotEqualTo(unsafe).matches("[0-9a-f-]{36}");
    }

    /** Health is public; metrics and other operational endpoints are for the owner only. */
    @Test
    void operationalEndpointsAreOwnerOnlyExceptHealth() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String assistant = api.login(api.createUser(owner, "ASSISTANT"), PASSWORD);

        assertThat(api.call("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
        assertThat(api.call("GET", "/actuator/metrics", null, null).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/actuator/metrics", assistant, null).statusCode()).isEqualTo(403);
        var metrics = api.call("GET", "/actuator/metrics/ausan.auth.logins", owner, null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("ausan.auth.logins");
        assertThat(api.call("GET", "/actuator/env", owner, null).statusCode()).isEqualTo(404);   // not exposed at all
    }
}
