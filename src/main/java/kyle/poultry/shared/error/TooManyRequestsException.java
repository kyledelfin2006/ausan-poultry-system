package kyle.poultry.shared.error;

/**
 * The server is already doing as much of this kind of work as it allows, so the request is refused for now (maps to
 * HTTP 429 with a {@code Retry-After} header). Nothing was started; the client may repeat the same request later.
 */
public class TooManyRequestsException extends RuntimeException {
    private final int retryAfterSeconds;

    /**
     * Creates the exception.
     *
     * @param message           client-visible explanation
     * @param retryAfterSeconds how long the client is told to wait before repeating, at least 1
     */
    public TooManyRequestsException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** {@return seconds to put in the {@code Retry-After} header} */
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
