package kyle.poultry.shared.error;

import kyle.poultry.support.ApiTestClient;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import kyle.poultry.TestcontainersConfiguration;

/**
 * End-to-end tests of the error contract: every kind of failure the frontend can meet has the documented HTTP status,
 * media type and stable {@code code} (see {@code docs/api/errors.md}). Runs the whole application against a real
 * database so the security chain and exception advice behave exactly as in production.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ErrorContractTests {

    private static final String JSON = "application/json";
    private static final String PROBLEM = "application/problem+json";
    private static final String DELIVERY_BODY = """
            {"customerName":"a","driverName":"b","fromBranchId":1,"destination":"d","paymentMethod":"CASH",
             "lines":[{"packageId":1,"quantity":1000000}]}""";

    @LocalServerPort int port;
    ApiTestClient api;
    String owner;

    /**
     * Creates the client and logs in as the owner before each test.
     *
     * @throws Exception when login fails
     */
    @BeforeEach
    void setUp() throws Exception {
        api = new ApiTestClient(port);
        owner = api.loginAsOwner();
    }

    /**
     * Asserts status, problem media type and the exact error code of a response.
     *
     * @param response the response to check
     * @param status   expected HTTP status
     * @param code     expected {@code code} member
     */
    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith(PROBLEM);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"").contains("\"status\":" + status);
    }

    /** Bean validation failures are 400 {@code VALIDATION_FAILED} and name every bad field. */
    @Test
    void validationFailureListsFieldsWithCode() throws Exception {
        var r = api.json("POST", "/api/products", owner, "{\"name\":\"\",\"unit\":\"\"}");
        assertProblem(r, 400, ErrorCodes.VALIDATION_FAILED);
        assertThat(r.body()).contains("name must not be blank").contains("unit must not be blank");
    }

    /** Spring MVC's own 400s (unreadable JSON, wrong types, missing parameter, bad header) are problem details too. */
    @Test
    void frameworkBadRequestsShareTheProblemShape() throws Exception {
        assertProblem(api.json("POST", "/api/products", owner, "{"), 400, ErrorCodes.BAD_REQUEST);
        assertProblem(api.json("GET", "/api/deliveries/abc", owner, null), 400, ErrorCodes.BAD_REQUEST);
        assertProblem(api.json("GET", "/api/deliveries?status=NOPE", owner, null), 400, ErrorCodes.BAD_REQUEST);
        assertProblem(api.json("GET", "/api/reports/summary", owner, null), 400, ErrorCodes.BAD_REQUEST);
        var longKey = api.send("POST", "/api/deliveries", owner, JSON, DELIVERY_BODY, Map.of("Idempotency-Key", "k".repeat(101)));
        assertProblem(longKey, 400, ErrorCodes.BAD_REQUEST);
    }

    /** A missing record is 404 {@code NOT_FOUND}; an unknown route is the same. */
    @Test
    void missingRecordsAreNotFound() throws Exception {
        assertProblem(api.json("GET", "/api/deliveries/99999", owner, null), 404, ErrorCodes.NOT_FOUND);
        assertProblem(api.json("GET", "/api/nope", owner, null), 404, ErrorCodes.NOT_FOUND);
    }

    /** Wrong verb and wrong media type keep their standard statuses with their own codes. */
    @Test
    void methodAndMediaTypeErrors() throws Exception {
        assertProblem(api.json("DELETE", "/api/products", owner, null), 405, ErrorCodes.METHOD_NOT_ALLOWED);
        assertProblem(api.send("POST", "/api/products", owner, "text/plain", "x", Map.of()), 415,
                ErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    /** The newer read endpoints (audit, series, deliveries filters, CSV) use the same codes as the older ones. */
    @Test
    void newerEndpointsShareTheErrorContract() throws Exception {
        assertProblem(api.json("GET", "/api/audit?from=2026-02-01&to=2026-01-01", owner, null), 422, ErrorCodes.RULE_VIOLATION);
        assertProblem(api.json("GET", "/api/deliveries?from=2026-02-01&to=2026-01-01", owner, null), 422, ErrorCodes.RULE_VIOLATION);
        assertProblem(api.json("GET", "/api/reports/series?from=2026-01-01&to=2026-01-31&granularity=WEEK", owner, null), 400,
                ErrorCodes.BAD_REQUEST);
        assertProblem(api.json("GET", "/api/reports/summary.csv?from=2026-02-01&to=2026-01-01", owner, null), 422,
                ErrorCodes.RULE_VIOLATION);
        assertProblem(api.json("GET", "/api/deliveries/99999/receipt", owner, null), 404, ErrorCodes.NOT_FOUND);
        // A password rule is a 422 with the shared code, and a bad token on the new /me endpoint is a bodiless 401.
        assertProblem(api.json("POST", "/api/users", owner,
                "{\"username\":\"pw-rule\",\"password\":\"aaaaaaaaaaaa\",\"displayName\":\"X\",\"role\":\"ASSISTANT\"}"),
                422, ErrorCodes.RULE_VIOLATION);
        assertThat(api.json("GET", "/api/auth/me", "garbage", null).statusCode()).isEqualTo(401);
    }

    /** A broken business rule is 422 {@code RULE_VIOLATION}. */
    @Test
    void businessRuleViolationIs422() throws Exception {
        assertProblem(api.json("GET", "/api/reports/summary?from=2026-02-01&to=2026-01-01", owner, null), 422,
                ErrorCodes.RULE_VIOLATION);
    }

    /** A clash with current state is 409 {@code CONFLICT}; a database constraint is 409 {@code DATA_CONFLICT}. */
    @Test
    void conflictsAreSplitIntoStateAndData() throws Exception {
        // A million packages can never be in stock, whatever other tests left in the shared database.
        assertProblem(api.json("POST", "/api/deliveries", owner, DELIVERY_BODY), 409, ErrorCodes.CONFLICT);
        // Branch names are unique; "Branch 1" is seeded, so creating it again hits the database constraint.
        assertProblem(api.json("POST", "/api/branches", owner, "{\"name\":\"Branch 1\"}"), 409, ErrorCodes.DATA_CONFLICT);
    }

    /** Wrong credentials are a problem detail with {@code UNAUTHENTICATED}; the message does not say which part was wrong. */
    @Test
    void badLoginIs401WithBody() throws Exception {
        var r = api.json("POST", "/api/auth/login", null, "{\"username\":\"nobody\",\"password\":\"whatever-long\"}");
        assertProblem(r, 401, ErrorCodes.UNAUTHENTICATED);
        assertThat(r.body()).contains("Invalid username or password");
    }

    /** The security layer's 401 and 403 are bare statuses: the status itself is the contract. */
    @Test
    void securityLayerAnswersAreEmptyBodies() throws Exception {
        var noToken = api.json("GET", "/api/products", null, null);
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(noToken.body()).isEmpty();

        String assistant = api.loginAsNewAssistant(owner, "assistant-errors");
        // Role check by @PreAuthorize on the controller.
        var annotated = api.json("POST", "/api/products", assistant, "{\"name\":\"z\",\"unit\":\"u\"}");
        assertThat(annotated.statusCode()).isEqualTo(403);
        assertThat(annotated.body()).isEmpty();
        // Role check thrown from inside a service: must still be 403, not turned into 500 by the catch-all handler.
        var inService = api.json("POST", "/api/deliveries/1/cancel", assistant, "{\"reason\":\"r\"}");
        assertThat(inService.statusCode()).isEqualTo(403);
        assertThat(inService.body()).isEmpty();
    }
}
