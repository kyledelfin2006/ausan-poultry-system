package kyle.poultry.delivery;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import kyle.poultry.shared.error.RuleViolation;
import org.springframework.data.jpa.domain.Specification;

/**
 * The optional filters of the delivery list and its status counts. Every field is optional and the ones given are
 * combined with AND. Package-private; built by {@link DeliveryController} and applied by {@link DeliveryService}.
 *
 * <p>Dates are business dates in the farm's zone and refer to the day the trip <em>started</em> ({@code startedAt}),
 * which is also the date booked in stock and finance. Both ends are inclusive.
 *
 * @param status   only this status
 * @param branchId only deliveries whose stock came from this branch
 * @param customer text the customer name must contain, ignoring case; blank counts as absent
 * @param from     first business date included
 * @param to       last business date included
 */
record DeliveryFilter(DeliveryStatus status, Long branchId, String customer, LocalDate from, LocalDate to) {

    /** The filter that keeps everything. */
    static final DeliveryFilter NONE = new DeliveryFilter(null, null, null, null, null);

    /**
     * Checks the combination of filters.
     *
     * @return this filter, for fluent use
     * @throws RuleViolation when both dates are given and {@code from} is after {@code to}
     */
    DeliveryFilter validated() {
        if (from != null && to != null && from.isAfter(to)) throw new RuleViolation("The start date must not be after the end date");
        return this;
    }

    /**
     * The same filter without the status, used to count every status for the same selection.
     *
     * @return a copy with {@code status} cleared
     */
    DeliveryFilter withoutStatus() { return new DeliveryFilter(null, branchId, customer, from, to); }

    /**
     * The same filter restricted to one status.
     *
     * @param s the status to keep
     * @return a copy with {@code status} set
     */
    DeliveryFilter withStatus(DeliveryStatus s) { return new DeliveryFilter(s, branchId, customer, from, to); }

    /**
     * Turns the filter into a database condition.
     *
     * @param zone the farm's zone, which decides where a business day starts and ends
     * @return a specification matching the deliveries that pass every given filter
     */
    Specification<Delivery> toSpecification(ZoneId zone) {
        // Day boundaries are computed once: [start of from, start of the day after to) in the farm's zone.
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        String needle = customer == null || customer.isBlank() ? null : customer.strip().toLowerCase();
        return (root, query, cb) -> {
            var conditions = new ArrayList<Predicate>();
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (branchId != null) conditions.add(cb.equal(root.get("fromBranchId"), branchId));
            // % and _ typed by the user are matched literally, not as wildcards.
            if (needle != null) conditions.add(cb.like(cb.lower(root.get("customerName")), "%" + escapeLike(needle) + "%", '\\'));
            if (fromInstant != null) conditions.add(cb.greaterThanOrEqualTo(root.get("startedAt"), fromInstant));
            if (toExclusive != null) conditions.add(cb.lessThan(root.get("startedAt"), toExclusive));
            return cb.and(conditions.toArray(new Predicate[0]));
        };
    }

    /**
     * Escapes the characters that have a meaning in a SQL LIKE pattern.
     *
     * @param text the text typed by the user
     * @return the text with {@code \}, {@code %} and {@code _} escaped by a backslash
     */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
