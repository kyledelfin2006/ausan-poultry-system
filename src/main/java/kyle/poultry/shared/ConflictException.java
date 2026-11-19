package kyle.poultry.shared;

/** The request is valid but clashes with current state (maps to HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
