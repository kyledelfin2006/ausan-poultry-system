package kyle.poultry.audit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the summary text of an {@link AuditAction#UPDATED} entry from before/after values, listing only the fields
 * that really changed. Mutable and single-use; create one per update.
 *
 * <pre>{@code
 * var changes = AuditChanges.of().field("price", before.price(), after.price()).field("active", before.active(), after.active());
 * if (!changes.isEmpty()) audit.record(AuditAction.UPDATED, AuditEntity.PACKAGE, id, changes.summary());
 * }</pre>
 */
public final class AuditChanges {
    private final List<String> parts = new ArrayList<>();

    /** Private: create instances with {@link #of()}. */
    private AuditChanges() {}

    /** {@return an empty set of changes} */
    public static AuditChanges of() { return new AuditChanges(); }

    /**
     * Records a field if (and only if) its value changed.
     *
     * @param name   the field name shown to the reader
     * @param before the old value, may be null
     * @param after  the new value, may be null
     * @return this, for chaining
     */
    public AuditChanges field(String name, Object before, Object after) {
        if (!same(before, after)) parts.add(name + ": " + show(before) + " -> " + show(after));
        return this;
    }

    /** {@return true when no field changed, so there is nothing to record} */
    public boolean isEmpty() { return parts.isEmpty(); }

    /** {@return the changed fields joined as {@code name: old -> new; name: old -> new}} */
    public String summary() { return String.join("; ", parts); }

    /**
     * Compares two values, treating numerically equal decimals as equal ({@code 70000.0} and {@code 70000.00}).
     *
     * @param a the first value
     * @param b the second value
     * @return true when they are the same for audit purposes
     */
    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }

    /**
     * Formats a value for the summary.
     *
     * @param v the value, may be null
     * @return {@code null} for null, the plain string otherwise (decimals without exponent notation)
     */
    private static String show(Object v) {
        if (v == null) return "null";
        return v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v);
    }
}
