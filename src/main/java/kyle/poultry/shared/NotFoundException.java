package kyle.poultry.shared;

/**
 * A record that was asked for by id does not exist (maps to HTTP 404).
 * <p>
 * Thrown by any module's lookup when an id is unknown; {@code ApiExceptionHandler} turns it into a 404.
 */
public class NotFoundException extends RuntimeException {

    /**
     * Creates the exception with a message of the form {@code "<what> <id> not found"}.
     *
     * @param what the kind of record, e.g. {@code "Product"}
     * @param id   the id that could not be found
     */
    public NotFoundException(String what, long id) { super(what + " " + id + " not found"); }
}
