package kyle.poultry.stock.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public representation of one ledger row.
 *
 * @param id            the movement id
 * @param productId     the product affected
 * @param branchId      the branch affected
 * @param type          the movement type
 * @param quantityDelta signed units added (positive) or removed (negative)
 * @param unitCost      cost per unit at 4 decimals, or {@code null} if none was recorded; assistant history reads
 *                      include it only for PURCHASE rows they recorded themselves
 * @param reason        the free-text reason, if any
 * @param occurredOn    the business date
 * @param sourceType    the kind of record that caused it (e.g. DELIVERY), or {@code null}
 * @param sourceId      the id of that record, or {@code null}
 * @param reversesId    the id of the movement this one undoes, or {@code null}
 * @param createdBy     the id of the user who recorded it
 * @param createdAt     the moment it was recorded
 */
public record MovementView(long id, long productId, long branchId, MovementType type, long quantityDelta,
                           @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal unitCost, String reason, LocalDate occurredOn,
                           String sourceType, Long sourceId, Long reversesId, long createdBy, Instant createdAt) {

    /**
     * |quantity| x unit cost, rounded to centavos.
     *
     * @return the absolute value of the movement at its unit cost, at 2 decimals; zero if no cost is recorded
     */
    public BigDecimal totalCost() {
        // No recorded cost counts as zero rather than failing.
        if (unitCost == null) return BigDecimal.ZERO.setScale(2);
        // Absolute quantity: the cost of removing 5 units equals the cost of adding 5 at the same price.
        return unitCost.multiply(BigDecimal.valueOf(Math.abs(quantityDelta))).setScale(2, RoundingMode.HALF_UP);
    }
}
