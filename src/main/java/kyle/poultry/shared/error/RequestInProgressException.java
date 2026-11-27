package kyle.poultry.shared.error;

/**
 * The same request (same Idempotency-Key and user) is still being processed by an earlier call (maps to HTTP 409 with a
 * {@code Retry-After} header).
 * <p>
 * Thrown instead of waiting indefinitely, so a waiting call gives its database connection back quickly. The client
 * should repeat the identical request after the delay; by then the first call has finished and the repeat returns its
 * result.
 */
public class RequestInProgressException extends ConflictException {

    /** Seconds the client is told to wait before repeating the request. */
    public static final int RETRY_AFTER_SECONDS = 1;

    /** Creates the exception with the fixed client-visible message. */
    public RequestInProgressException() {
        super("The same request is still being processed; repeat it shortly");
    }
}
