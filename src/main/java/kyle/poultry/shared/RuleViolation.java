package kyle.poultry.shared;

/**
 * A business rule was broken by the request (maps to HTTP 422).
 * <p>
 * Thrown by any module when input is well-formed but not acceptable (for example an inactive product or
 * a wrong quantity sign). {@code ApiExceptionHandler} turns it into a 422 response.
 */
public class RuleViolation extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message client-visible explanation of the rule that was broken
     */
    public RuleViolation(String message) { super(message); }
}
