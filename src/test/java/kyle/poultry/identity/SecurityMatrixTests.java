package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.support.HttpApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The security matrix: EVERY HTTP endpoint of the application is listed once with who may call it, and each row is
 * exercised as anonymous, as an assistant and as an owner over real HTTP.
 *
 * <ul>
 *   <li>PUBLIC: reachable without a token.</li>
 *   <li>ANY_ROLE: needs a valid token; owner and assistant are both let in (they may still get 400, 404 or 422 for the
 *       dummy request, which is fine: only 401 and 403 are failures here).</li>
 *   <li>OWNER_ONLY: anonymous 401, assistant 403, owner let in.</li>
 * </ul>
 *
 * A second test compares the matrix with the endpoints Spring actually mapped, in both directions. A new endpoint
 * therefore cannot ship without a row (and an explicit decision about who may call it), and a removed endpoint cannot
 * leave a stale row. Owner-only rows send a VALID body: body validation runs before the role check on method-secured
 * controllers, so an invalid body would answer 400 instead of 403 and hide a missing rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SecurityMatrixTests {

    /** Who may call an endpoint. */
    enum Access { PUBLIC, ANY_ROLE, OWNER_ONLY }

    /**
     * One row of the matrix.
     *
     * @param method  HTTP verb
     * @param pattern the mapped path pattern, for example {@code /api/deliveries/{id}}
     * @param access  who may call it
     * @param path    a concrete path to call (placeholders filled in)
     * @param body    a request body that passes validation, or null
     */
    record Row(String method, String pattern, Access access, String path, String body) {
        /** {@return the key used to compare with Spring's mappings, such as {@code GET /api/products}} */
        String key() { return method + " " + pattern; }
    }

    /** Valid owner-only bodies; see the class comment for why validity matters. */
    static final String PRODUCT = "{\"name\":\"matrix\",\"unit\":\"pc\"}";
    static final String PACKAGE = "{\"sku\":\"MX\",\"name\":\"matrix\",\"price\":10.00,\"items\":[{\"productId\":1,\"quantity\":1}]}";

    static final List<Row> MATRIX = List.of(
            // identity
            new Row("POST", "/api/auth/login", Access.PUBLIC, "/api/auth/login",
                    "{\"username\":\"" + TestcontainersConfiguration.OWNER_USERNAME + "\",\"password\":\"" + TestcontainersConfiguration.OWNER_PASSWORD + "\"}"),
            new Row("GET", "/api/auth/me", Access.ANY_ROLE, "/api/auth/me", null),
            new Row("POST", "/api/auth/logout", Access.ANY_ROLE, "/api/auth/logout", null),
            new Row("POST", "/api/auth/password", Access.ANY_ROLE, "/api/auth/password",
                    "{\"currentPassword\":\"not-the-password\",\"newPassword\":\"another-long-password\"}"),
            new Row("POST", "/api/users", Access.OWNER_ONLY, "/api/users",
                    "{\"username\":\"matrix-user\",\"password\":\"a-long-enough-password\",\"displayName\":\"M\",\"role\":\"ASSISTANT\"}"),
            new Row("GET", "/api/users", Access.OWNER_ONLY, "/api/users", null),
            new Row("PATCH", "/api/users/{id}", Access.OWNER_ONLY, "/api/users/999999", "{}"),
            new Row("POST", "/api/users/{id}/password", Access.OWNER_ONLY, "/api/users/999999/password", "{\"newPassword\":\"a-long-enough-password\"}"),
            // audit
            new Row("GET", "/api/audit", Access.OWNER_ONLY, "/api/audit", null),
            // catalog
            new Row("GET", "/api/products", Access.ANY_ROLE, "/api/products", null),
            new Row("POST", "/api/products", Access.OWNER_ONLY, "/api/products", PRODUCT),
            new Row("PATCH", "/api/products/{id}", Access.OWNER_ONLY, "/api/products/999999", "{}"),
            new Row("GET", "/api/branches", Access.ANY_ROLE, "/api/branches", null),
            new Row("POST", "/api/branches", Access.OWNER_ONLY, "/api/branches", "{\"name\":\"matrix\"}"),
            new Row("PATCH", "/api/branches/{id}", Access.OWNER_ONLY, "/api/branches/999999", "{}"),
            new Row("GET", "/api/packages", Access.ANY_ROLE, "/api/packages", null),
            new Row("POST", "/api/packages", Access.OWNER_ONLY, "/api/packages", PACKAGE),
            new Row("PATCH", "/api/packages/{id}", Access.OWNER_ONLY, "/api/packages/999999", "{}"),
            // stock
            new Row("POST", "/api/stock/movements", Access.ANY_ROLE, "/api/stock/movements", "{}"),
            new Row("GET", "/api/stock/balances", Access.ANY_ROLE, "/api/stock/balances?branchId=1", null),
            new Row("GET", "/api/stock/movements", Access.ANY_ROLE, "/api/stock/movements?productId=1&branchId=1", null),
            // finance
            new Row("POST", "/api/finance/expenses", Access.ANY_ROLE, "/api/finance/expenses", "{}"),
            new Row("GET", "/api/finance/entries", Access.ANY_ROLE, "/api/finance/entries", null),
            // deliveries
            new Row("POST", "/api/deliveries", Access.ANY_ROLE, "/api/deliveries", "{}"),
            new Row("GET", "/api/deliveries", Access.ANY_ROLE, "/api/deliveries", null),
            new Row("GET", "/api/deliveries/{id}", Access.ANY_ROLE, "/api/deliveries/999999", null),
            new Row("POST", "/api/deliveries/{id}/done", Access.ANY_ROLE, "/api/deliveries/999999/done", "{\"completedAt\":\"2026-01-01T00:00:00Z\"}"),
            new Row("POST", "/api/deliveries/{id}/cancel", Access.OWNER_ONLY, "/api/deliveries/999999/cancel", "{\"reason\":\"matrix\"}"),
            new Row("POST", "/api/deliveries/{id}/refund", Access.OWNER_ONLY, "/api/deliveries/999999/refund", "{\"amount\":1.00,\"method\":\"CASH\"}"),
            // reports
            new Row("GET", "/api/reports/summary", Access.ANY_ROLE, "/api/reports/summary?from=2026-01-01&to=2026-01-02", null),
            new Row("GET", "/api/reports/profit", Access.OWNER_ONLY, "/api/reports/profit?from=2026-01-01&to=2026-01-02", null),
            new Row("GET", "/api/reports/inventory-value", Access.ANY_ROLE, "/api/reports/inventory-value", null),
            // operations (not Spring MVC controllers, so outside the mapping comparison)
            new Row("GET", "/actuator/health", Access.PUBLIC, "/actuator/health", null),
            new Row("GET", "/actuator/metrics", Access.OWNER_ONLY, "/actuator/metrics", null));

    @LocalServerPort int port;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    /** {@return a client for the running application} */
    HttpApiClient api() { return new HttpApiClient(port); }

    /**
     * Calls one row with the given token.
     *
     * @param api   the client
     * @param row   the row
     * @param token the bearer token, or null for anonymous
     * @param asOwner whether the caller is the owner (owner-only rows then send an invalid body, see below)
     * @return the HTTP status
     * @throws Exception if the connection fails
     */
    private int status(HttpApiClient api, Row row, String token, boolean asOwner) throws Exception {
        // The owner is let through, so a VALID body would really create or change data (and pollute other tests):
        // send an empty, invalid body instead. A 400/404 still proves the owner got past the role check.
        String body = asOwner && row.access() == Access.OWNER_ONLY && row.body() != null ? "{}" : row.body();
        return api.call(row.method(), row.path(), token, body).statusCode();
    }

    /** Every row behaves as declared for anonymous callers, assistants and owners. */
    @Test
    void everyEndpointEnforcesItsDeclaredAccess() throws Exception {
        var api = api();
        String owner = api.ownerToken();
        String assistantName = api.createUser(owner, "ASSISTANT");
        String assistant = api.login(assistantName, HttpApiClient.PASSWORD);
        List<String> failures = new ArrayList<>();

        for (Row row : MATRIX) {
            // Logout and password endpoints end ALL sessions of the caller. Calling them as the shared owner or
            // assistant would kill the tokens the other rows use, so they get brand-new users of their own.
            boolean consumes = row.path().equals("/api/auth/logout") || row.path().equals("/api/auth/password");
            String ownerToken = consumes ? api.login(api.createUser(owner, "OWNER"), HttpApiClient.PASSWORD) : owner;
            String assistantToken = consumes ? api.login(api.createUser(owner, "ASSISTANT"), HttpApiClient.PASSWORD) : assistant;

            int anonymous = status(api, row, null, false);
            int asAssistant = status(api, row, assistantToken, false);
            int asOwner = status(api, row, ownerToken, true);

            switch (row.access()) {
                case PUBLIC -> {
                    if (anonymous == 401 || anonymous == 403) failures.add(row.key() + " anonymous should be allowed, got " + anonymous);
                }
                case ANY_ROLE -> {
                    if (anonymous != 401) failures.add(row.key() + " anonymous should be 401, got " + anonymous);
                    if (asAssistant == 401 || asAssistant == 403) failures.add(row.key() + " assistant should be allowed, got " + asAssistant);
                    if (asOwner == 401 || asOwner == 403) failures.add(row.key() + " owner should be allowed, got " + asOwner);
                }
                case OWNER_ONLY -> {
                    if (anonymous != 401) failures.add(row.key() + " anonymous should be 401, got " + anonymous);
                    if (asAssistant != 403) failures.add(row.key() + " assistant should be 403, got " + asAssistant);
                    if (asOwner == 401 || asOwner == 403) failures.add(row.key() + " owner should be allowed, got " + asOwner);
                }
            }
        }
        assertThat(failures).as("access violations").isEmpty();
    }

    /** The matrix lists exactly the {@code /api} endpoints the application maps: nothing missing, nothing stale. */
    @Test
    void theMatrixCoversEveryMappedEndpoint() {
        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api")) continue;
                var methods = info.getMethodsCondition().getMethods();
                // An endpoint with no explicit verb would answer every verb: list it as ALL so it can never hide.
                if (methods.isEmpty()) mapped.add("ALL " + pattern);
                for (RequestMethod method : methods) mapped.add(method + " " + pattern);
            }
        }
        Set<String> declared = new TreeSet<>();
        for (Row row : MATRIX) if (row.pattern().startsWith("/api")) declared.add(row.key());

        Set<String> missing = new TreeSet<>(mapped); missing.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared); stale.removeAll(mapped);
        assertThat(missing).as("endpoints with no row in SecurityMatrixTests.MATRIX (decide who may call them, then add one)").isEmpty();
        assertThat(stale).as("rows for endpoints that no longer exist").isEmpty();
    }
}
