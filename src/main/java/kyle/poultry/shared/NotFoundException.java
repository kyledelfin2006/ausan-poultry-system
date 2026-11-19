package kyle.poultry.shared;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, long id) { super(what + " " + id + " not found"); }
}
