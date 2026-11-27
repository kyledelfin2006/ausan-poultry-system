package kyle.poultry.shared;

import java.util.List;
import org.springframework.http.HttpStatusCode;

/**
 * The catalogue of stable machine-readable error codes returned in the {@code code} property of every
 * problem-detail response.
 * <p>
 * Clients branch on {@code code} (never on the human-readable {@code detail}, which may be reworded). A code is part of
 * the API contract: it may be added to, but an existing code is never renamed or reused for a different meaning.
 * Documented for the frontend in {@code docs/api/errors.md}; the list is also published in the OpenAPI schema.
 */
public final class ErrorCodes {

    /** HTTP 400: the request body failed bean validation; {@code detail} lists every offending field. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** HTTP 400: the request could not be understood (unreadable JSON, missing or ill-typed parameter, bad header). */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** HTTP 401: credentials were refused (wrong username or password at login). */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /** HTTP 403: authenticated, but the role may not perform the action. */
    public static final String FORBIDDEN = "FORBIDDEN";

    /** HTTP 404: the addressed record (or route) does not exist. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** HTTP 405: the path exists but not for this HTTP method. */
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /** HTTP 406: the server cannot produce a representation the client accepts. */
    public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";

    /** HTTP 409: the request clashes with the current state (insufficient stock, delivery no longer ACTIVE, ...). */
    public static final String CONFLICT = "CONFLICT";

    /** HTTP 409: a database constraint rejected the change (duplicate name, referenced row, ...). */
    public static final String DATA_CONFLICT = "DATA_CONFLICT";

    /**
     * HTTP 409: an identical request (same {@code Idempotency-Key}) is still running. Unlike {@link #CONFLICT} this is
     * safe to repeat: wait for {@code Retry-After} seconds, then send the same request with the same key.
     */
    public static final String REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS";

    /** HTTP 415: the request body is not JSON. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    /** HTTP 422: the request is well-formed but a business rule forbids it. */
    public static final String RULE_VIOLATION = "RULE_VIOLATION";

    /** HTTP 429: too many failed logins, or too many exports running at once; wait for the {@code Retry-After} header. */
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

    /** HTTP 500: an unexpected server error; the response carries no internal detail. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Every code above, in documentation order; used to publish the allowed values in the OpenAPI schema. */
    public static final List<String> ALL = List.of(VALIDATION_FAILED, BAD_REQUEST, UNAUTHENTICATED, FORBIDDEN,
            NOT_FOUND, METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, CONFLICT, DATA_CONFLICT, REQUEST_IN_PROGRESS, UNSUPPORTED_MEDIA_TYPE,
            RULE_VIOLATION, TOO_MANY_REQUESTS, INTERNAL_ERROR);

    /** Not instantiable: constants and a lookup only. */
    private ErrorCodes() {}

    /**
     * Picks the generic code for a status when no more specific code was chosen by the exception handler.
     * <p>
     * Used for problem details produced by Spring itself (405, 415, ...) and by code that builds its own
     * {@code ProblemDetail} (the login throttle's 429). Statuses without a dedicated code are not silently dropped:
     * they fall back to {@link #BAD_REQUEST} for other 4xx and {@link #INTERNAL_ERROR} for 5xx so a client never
     * sees a response without a code.
     *
     * @param status the HTTP status of the problem response
     * @return the code that goes with that status
     */
    public static String forStatus(HttpStatusCode status) {
        // Compare by numeric value so unknown/custom status codes still resolve instead of throwing.
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> RULE_VIOLATION;
            case 429 -> TOO_MANY_REQUESTS;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}
