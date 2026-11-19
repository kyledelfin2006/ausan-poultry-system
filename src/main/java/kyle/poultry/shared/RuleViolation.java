package kyle.poultry.shared;

/** A business rule was broken by the request (maps to HTTP 422). */
public class RuleViolation extends RuntimeException {
    public RuleViolation(String message) { super(message); }
}
