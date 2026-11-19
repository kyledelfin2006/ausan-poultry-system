package kyle.poultry.shared;

/**
 * The request is valid but clashes with current state (maps to HTTP 409).
 * <p>
 * Base class for state conflicts such as insufficient stock. Any module may throw it or extend it;
 * {@code ApiExceptionHandler} turns it into a 409 response.
 */
public class ConflictException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message client-visible explanation of the conflict
     */
    public ConflictException(String message) { super(message); }
}
