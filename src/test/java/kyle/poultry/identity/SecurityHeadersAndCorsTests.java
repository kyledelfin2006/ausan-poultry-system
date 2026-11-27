package kyle.poultry.identity;

import static kyle.poultry.support.HttpApiClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.support.HttpApiClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * End-to-end tests over real HTTP of the browser-facing protections: CORS (allowed and refused origins), security
 * response headers, the request id header, and who may read operational (actuator) endpoints. The allowed origins
 * come from the default {@code application.yaml} (local frontend dev servers).
 */
@Tag("integration")
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

    /**
     * A browser can only read an error body, status and {@code Retry-After} when the error response itself carries the
     * CORS headers. The CORS filter runs before the security chain, so 401 and 403 must have them too, as must the
     * application's own 422 and 429 answers.
     */
    @Test
    void errorResponsesFromAnAllowedOriginCarryCorsHeadersSoTheUiCanReadThem() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String assistant = api.login(api.createUser(owner, "ASSISTANT"), PASSWORD);

        var unauthorized = api.call("GET", "/api/products", null, null, "Origin", ALLOWED);
        var forbidden = api.call("GET", "/api/audit", assistant, null, "Origin", ALLOWED);
        var invalid = api.call("POST", "/api/products", owner, "{}", "Origin", ALLOWED);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(invalid.statusCode()).isEqualTo(400);
        for (var r : List.of(unauthorized, forbidden, invalid)) {
            assertThat(header(r, "Access-Control-Allow-Origin")).as("status %s", r.statusCode()).isEqualTo(ALLOWED);
            assertThat(header(r, "Access-Control-Expose-Headers")).contains("Retry-After", "X-Request-Id");
        }

        // The login throttle's 429 is the one the UI must read Retry-After from; use a throwaway name so no real account is throttled.
        String name = "cors-" + UUID.randomUUID().toString().substring(0, 8);
        String bad = "{\"username\":\"" + name + "\",\"password\":\"wrong-password-value\"}";
        for (int i = 0; i < 5; i++) assertThat(api.call("POST", "/api/auth/login", null, bad, "Origin", ALLOWED).statusCode()).isEqualTo(401);
        var throttled = api.call("POST", "/api/auth/login", null, bad, "Origin", ALLOWED);
        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(header(throttled, "Retry-After")).matches("[0-9]+");
        assertThat(header(throttled, "Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(header(throttled, "Access-Control-Expose-Headers")).contains("Retry-After");
    }

    /** A preflight asking for a header or method the API does not use is refused, so the allow-list is real. */
    @Test
    void preflightForAnUnlistedHeaderOrMethodIsRefused() throws Exception {
        var api = api();
        var header = api.call("OPTIONS", "/api/deliveries", null, null,
                "Origin", ALLOWED, "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "x-not-allowed");
        assertThat(header.statusCode()).isEqualTo(403);
        assertThat(header(header, "Access-Control-Allow-Origin")).isNull();

        var method = api.call("OPTIONS", "/api/deliveries", null, null,
                "Origin", ALLOWED, "Access-Control-Request-Method", "TRACE");
        assertThat(method.statusCode()).isEqualTo(403);
        assertThat(header(method, "Access-Control-Allow-Origin")).isNull();
    }

    /**
     * The API authenticates only by bearer header: an ambient cookie (the credential a browser attaches by itself, which
     * is what makes CSRF possible) grants nothing, and no response ever sets one. That is why CSRF protection is off.
     */
    @Test
    void cookiesNeverAuthenticateAndNoResponseSetsOne() throws Exception {
        var api = api();
        String token = api.ownerToken();

        var withCookieOnly = api.call("GET", "/api/products", null, null,
                "Cookie", "JSESSIONID=abc; token=" + token, "Origin", ALLOWED);
        assertThat(withCookieOnly.statusCode()).isEqualTo(401);

        // A state-changing call authenticated only by a cookie must not change anything.
        String name = "csrf-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"unit\":\"pc\"}";
        // From a foreign origin the browser rule refuses it first (403); without an Origin the missing bearer does (401).
        assertThat(api.call("POST", "/api/products", null, body, "Cookie", "token=" + token, "Origin", EVIL).statusCode()).isEqualTo(403);
        assertThat(api.call("POST", "/api/products", null, body, "Cookie", "token=" + token).statusCode()).isEqualTo(401);
        assertThat(api.call("GET", "/api/products", token, null).body()).doesNotContain(name);

        var login = api.call("POST", "/api/auth/login", null, "{\"username\":\"" + TestcontainersConfiguration.OWNER_USERNAME
                + "\",\"password\":\"" + TestcontainersConfiguration.OWNER_PASSWORD + "\"}");
        var authed = api.call("GET", "/api/products", token, null);
        assertThat(login.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(authed.headers().allValues("Set-Cookie")).isEmpty();
    }
}
