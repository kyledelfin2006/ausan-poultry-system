package kyle.poultry.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kyle.poultry.shared.ErrorCodes;

/**
 * Publishes the error side of the contract: the shared {@code Problem} schema and, for every operation, the error
 * statuses it can answer.
 * <p>
 * Statuses are derived from the shape of the operation (public or secured, has input, has an id in the path) plus
 * {@link #BUSINESS_ERRORS}, a small hand-kept table for business-rule outcomes (409, 422 and 404 for ids in the body)
 * that cannot be seen from the code. When an endpoint can fail in one of those ways, add a line to the table; the
 * snapshot test then shows the contract change in review.
 */
final class ErrorResponses {

    /** Paths that need no token; everything else is secured. Mirrors the permit-all rule in the security config. */
    static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login");

    /** Media type of every error body (RFC 9457). */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Reference to the shared error schema. */
    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    /**
     * Business-rule outcomes keyed by {@code "METHOD /path"}. The value lists the extra HTTP statuses the operation can
     * answer: 409 (conflict), 422 (rule violation) and 404 when the body references an id that may not exist.
     */
    static final Map<String, Set<Integer>> BUSINESS_ERRORS = Map.ofEntries(
            Map.entry("POST /api/users", Set.of(409)),
            Map.entry("PATCH /api/users/{id}", Set.of(422)),
            Map.entry("POST /api/auth/password", Set.of(422)),
            Map.entry("POST /api/products", Set.of(409)),
            Map.entry("PATCH /api/products/{id}", Set.of(409)),
            Map.entry("POST /api/branches", Set.of(409)),
            Map.entry("PATCH /api/branches/{id}", Set.of(409)),
            Map.entry("POST /api/packages", Set.of(404, 409, 422)),
            Map.entry("PATCH /api/packages/{id}", Set.of(409)),
            Map.entry("POST /api/stock/movements", Set.of(404, 409, 422)),
            Map.entry("POST /api/finance/expenses", Set.of(422)),
            Map.entry("GET /api/finance/entries", Set.of(422)),
            Map.entry("POST /api/deliveries", Set.of(404, 409, 422)),
            Map.entry("POST /api/deliveries/{id}/done", Set.of(409)),
            Map.entry("POST /api/deliveries/{id}/cancel", Set.of(409)),
            Map.entry("POST /api/deliveries/{id}/refund", Set.of(422)),
            Map.entry("GET /api/reports/summary", Set.of(422)),
            Map.entry("GET /api/reports/profit", Set.of(422)));

    /** Not instantiable: static helpers only. */
    private ErrorResponses() {}

    /**
     * Registers the {@code Problem} component and adds error responses to every operation.
     *
     * @param openApi the scanned document, modified in place
     */
    static void apply(OpenAPI openApi) {
        openApi.getComponents().addSchemas("Problem", problemSchema());
        openApi.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) -> describe(path, method, operation)));
    }

    /**
     * Builds the RFC 9457 error object including the allowed {@code code} values.
     *
     * @return the {@code Problem} component schema
     */
    private static Schema<?> problemSchema() {
        var code = new StringSchema().description("Stable machine-readable error code; branch on this, not on `detail`.");
        code.setEnum(ErrorCodes.ALL);
        return new ObjectSchema()
                .description("RFC 9457 problem detail returned for every error that has a body.")
                .addProperty("title", new StringSchema().description("Short status text, e.g. `Unprocessable Content`."))
                .addProperty("status", new IntegerSchema().description("HTTP status, repeated in the body."))
                .addProperty("detail", new StringSchema().description("Human-readable explanation, safe to show to the user."))
                .addProperty("instance", new StringSchema().description("The request path that failed."))
                .addProperty("code", code)
                .required(List.of("title", "status", "code"));
    }

    /**
     * Adds the error responses that apply to one operation.
     *
     * @param path      the URL template, e.g. {@code /api/deliveries/{id}}
     * @param method    the HTTP method
     * @param operation the operation to extend
     */
    private static void describe(String path, PathItem.HttpMethod method, Operation operation) {
        var responses = operation.getResponses();
        if (PUBLIC_PATHS.contains(path)) {
            // An empty requirement list overrides the global one: no token needed here.
            operation.setSecurity(List.of());
            responses.addApiResponse("401", problem("Wrong username or password (`UNAUTHENTICATED`)."));
            responses.addApiResponse("429", throttled());
        } else {
            responses.addApiResponse("401", empty("Missing, invalid, expired or revoked token. Empty body."));
            responses.addApiResponse("403", empty("Authenticated, but the role may not do this. Empty body."));
        }
        boolean hasInput = operation.getRequestBody() != null
                || (operation.getParameters() != null && !operation.getParameters().isEmpty());
        if (hasInput) {
            responses.addApiResponse("400", problem(
                    "Invalid input: `VALIDATION_FAILED` (body fields) or `BAD_REQUEST` (unreadable body, bad parameter)."));
        }
        Set<Integer> business = BUSINESS_ERRORS.getOrDefault(method + " " + path, Set.of());
        // A path variable can point at a record that does not exist; a business entry may add 404 for ids in the body.
        if (path.contains("{") || business.contains(404)) {
            responses.addApiResponse("404", problem("A referenced record does not exist (`NOT_FOUND`)."));
        }
        if (business.contains(409)) {
            responses.addApiResponse("409", problem(
                    "Clashes with current state or existing data (`CONFLICT` or `DATA_CONFLICT`)."));
        }
        if (business.contains(422)) {
            responses.addApiResponse("422", problem("A business rule forbids it (`RULE_VIOLATION`)."));
        }
    }

    /**
     * A response with a problem-detail body.
     *
     * @param description what the status means for this operation
     * @return the response referencing the shared {@code Problem} schema
     */
    private static ApiResponse problem(String description) {
        var media = new MediaType().schema(new Schema<>().$ref(PROBLEM_REF));
        return new ApiResponse().description(description).content(new Content().addMediaType(PROBLEM_JSON, media));
    }

    /**
     * A response with no body, as produced by the security layer.
     *
     * @param description what the status means
     * @return the response without content
     */
    private static ApiResponse empty(String description) {
        return new ApiResponse().description(description);
    }

    /**
     * The login throttle's answer: a problem body plus the {@code Retry-After} header.
     *
     * @return the 429 response
     */
    private static ApiResponse throttled() {
        var retryAfter = new Header().description("Seconds to wait before trying again.").schema(new IntegerSchema());
        return problem("Too many failed logins for this username and address (`TOO_MANY_REQUESTS`).")
                .addHeaderObject("Retry-After", retryAfter);
    }
}
