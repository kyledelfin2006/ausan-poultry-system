package kyle.poultry.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.SourceRef;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public face of the stock module. All callers must join the caller's transaction (they do, by default).
 * <p>
 * Stock is an append-only ledger of movements plus a balance per product and branch. The contract for every
 * method that changes stock:
 * <ul>
 *   <li><b>Transactions:</b> the work runs in the caller's transaction when there is one, otherwise in a new
 *       one. If the caller's transaction rolls back, every movement and balance change made here rolls back
 *       with it.</li>
 *   <li><b>Never negative:</b> a balance can never drop below zero. The check and the update are one atomic
 *       database statement, so two concurrent callers cannot both take the last units.</li>
 *   <li><b>Ordering and locking:</b> a movement locks its balance row until the transaction ends, so
 *       concurrent movements on the same product and branch are applied one after another. Multi-line calls
 *       process lines in ascending product id so concurrent callers cannot deadlock.</li>
 *   <li><b>Failures:</b> {@link kyle.poultry.shared.NotFoundException} (404) for unknown products or branches,
 *       {@link kyle.poultry.shared.RuleViolation} (422) for inactive ones or broken input rules,
 *       {@link InsufficientStockException} (409) for shortages.</li>
 * </ul>
 * Callers: the web controller and the delivery module. The query methods are read-only.
 */
public interface StockApi {

    /**
     * Manual movement: opening, purchase, donation, usage or adjustment.
     * A PURCHASE also records the cash paid in the finance ledger, in the same transaction.
     * <p>
     * Validated: the type is one a person may enter ({@link MovementType#MANUAL}); opening and adjustment
     * need the owner role; future dates are rejected and assistants may backdate at most 7 calendar days;
     * the quantity sign matches the type; usage and
     * adjustment carry a reason; a purchase has a payment method and a unit cost above zero; the product
     * and branch exist and are active. Validation happens before any write.
     *
     * @param cmd   the movement to record
     * @param actor the signed-in user, stored as the creator and checked for owner-only types
     * @return the saved movement, including the cost stamped on it
     * @throws kyle.poultry.shared.RuleViolation if the type is not manual, the date is missing or outside the
     *         actor's allowed range, the sign or cost is wrong for the type, a reason or payment method is missing,
     *         or the product or branch is inactive
     * @throws org.springframework.security.access.AccessDeniedException if a non-owner records opening
     *         stock or an adjustment
     * @throws InsufficientStockException if a negative quantity exceeds what the branch holds
     * @throws kyle.poultry.shared.NotFoundException if the product or branch does not exist
     */
    MovementView record(StockCommand cmd, Actor actor);

    /**
     * Delivery stock-out for all lines, all-or-nothing within the caller's transaction.
     * Each returned movement carries the average cost at that moment ({@link MovementView#totalCost()} is the
     * cost of goods).
     * <p>
     * Lines are processed in ascending product id (a fixed lock order that avoids deadlocks). If any line fails,
     * the exception rolls back the caller's transaction, so earlier lines are not kept.
     *
     * @param lines    the products and positive quantities to remove
     * @param branchId the branch the stock leaves
     * @param source   the delivery (or other record) that causes the stock-out
     * @param on       the business date of the movement
     * @param actor    the signed-in user, stored as the creator
     * @return one movement per line, in ascending product id order
     * @throws InsufficientStockException if any line exceeds the branch balance
     * @throws kyle.poultry.shared.RuleViolation if a quantity is not positive or a product or branch is inactive
     * @throws kyle.poultry.shared.NotFoundException if a product or branch does not exist
     */
    List<MovementView> deduct(List<StockLine> lines, long branchId, SourceRef source, LocalDate on, Actor actor);

    /**
     * Restores every not-yet-reversed DELIVERY_OUT for the source at its original cost. Safe to call twice.
     * <p>
     * A second call finds nothing left to reverse and returns an empty list. Original movements are never
     * edited or deleted; each reversal is a new row pointing at the movement it undoes.
     *
     * @param source the record whose stock-outs are to be undone
     * @param reason the explanation stored on each reversal
     * @param on     the business date of the reversals
     * @param actor  the signed-in user, stored as the creator
     * @return the reversal movements created by this call (empty if nothing was left to reverse)
     */
    List<MovementView> reverseAllFor(SourceRef source, String reason, LocalDate on, Actor actor);

    /**
     * Current quantity held.
     *
     * @param productId the product
     * @param branchId  the branch
     * @return the units on hand, or 0 if the branch has never held the product
     */
    long balance(long productId, long branchId);

    /**
     * Weighted average cost per unit (PHP, 4 decimals); zero when the branch has never held the product.
     *
     * @param productId the product
     * @param branchId  the branch
     * @return the average cost per unit at 4 decimals
     */
    BigDecimal averageCost(long productId, long branchId);

    /**
     * Lists current balances with product names and average cost.
     *
     * @param branchId the branch to list, or {@code null} for all branches
     * @return balances ordered by branch id, then product id
     */
    List<BalanceView> balances(Long branchId);

    /**
     * Pages through the movement history of one product at one branch, newest first.
     *
     * @param productId the product
     * @param branchId  the branch
     * @param page      the page request
     * @return a page of movements ordered by business date then id, both descending
     */
    Page<MovementView> history(long productId, long branchId, Pageable page);

    /**
     * Every movement caused by one source record (for example a delivery's stock-outs and, if it was cancelled, their
     * reversals), oldest first. Read-only; used to cost what a delivery contained.
     *
     * @param source the record that caused the movements
     * @return its movements ordered by id ascending, empty when it caused none
     */
    List<MovementView> movementsFor(SourceRef source);

    /**
     * Stock per product across all branches with low-stock flags, ordered by product name. A branch is low on a product
     * when the product has a reorder level and the branch holds at most that many units; a product is low when any of
     * its branches is low (a product with a level that no branch has ever held counts as low too). Inactive products are
     * listed only while some branch still holds them. Branches that never held a product are not listed for it.
     *
     * @param lowOnly true to return only the products that are low
     * @return one entry per product
     */
    List<ProductStockView> summary(boolean lowOnly);

    /**
     * Lists every reorder level that is set, ordered by product name.
     *
     * @return the configured levels
     */
    List<ReorderLevelView> reorderLevels();

    /**
     * Sets (or replaces) the reorder level of a product. Owner only.
     *
     * @param productId the product, which must exist and be active
     * @param level     units at or below which a branch is low; 0 or more
     * @param actor     who sets it; must be the owner
     * @return the stored level
     * @throws org.springframework.security.access.AccessDeniedException if the actor is not the owner
     * @throws kyle.poultry.shared.RuleViolation                         if the level is negative or the product is inactive
     * @throws kyle.poultry.shared.NotFoundException                     if the product does not exist
     */
    ReorderLevelView setReorderLevel(long productId, long level, Actor actor);

    /**
     * Removes the reorder level of a product, so it is never flagged low. Owner only.
     *
     * @param productId the product
     * @param actor     who removes it; must be the owner
     * @throws org.springframework.security.access.AccessDeniedException if the actor is not the owner
     * @throws kyle.poultry.shared.NotFoundException                     if the product has no level
     */
    void clearReorderLevel(long productId, Actor actor);

    /**
     * Input for a manual movement.
     *
     * @param productId     the product affected
     * @param branchId      the branch affected
     * @param type          the movement type, which decides the legal sign of the quantity
     * @param quantityDelta signed units: positive adds stock, negative removes it, zero is never allowed
     * @param unitCost      required for PURCHASE; OPENING and DONATION default to zero
     * @param reason        free text, required for USAGE and ADJUSTMENT
     * @param occurredOn    the business date; assistants may backdate at most 7 days, owners may use any past date
     * @param paymentMethod required for PURCHASE (how the supplier was paid)
     */
    record StockCommand(long productId, long branchId, MovementType type, long quantityDelta,
                        BigDecimal unitCost, String reason, LocalDate occurredOn, PaymentMethod paymentMethod) {}

    /**
     * One line of a stock-out request.
     *
     * @param productId the product to remove
     * @param quantity  the number of units to remove (positive)
     */
    record StockLine(long productId, long quantity) {}

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
    record MovementView(long id, long productId, long branchId, MovementType type, long quantityDelta,
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

    /**
     * Public representation of the current stock of one product at one branch.
     *
     * @param productId   the product
     * @param productName the product's name
     * @param unit        the product's unit of measure
     * @param branchId    the branch
     * @param quantity    units on hand
     * @param averageCost weighted average cost per unit at 4 decimals; omitted from assistant responses
     */
    record BalanceView(long productId, String productName, String unit, long branchId, long quantity,
                       @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal averageCost) {}

    /**
     * One branch's stock of a product inside a {@link ProductStockView}.
     *
     * @param branchId    the branch
     * @param quantity    units on hand
     * @param averageCost weighted average cost per unit at 4 decimals; omitted from assistant responses
     * @param low         true when the product has a reorder level and {@code quantity} is at or below it
     */
    record BranchStockView(long branchId, long quantity,
                           @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal averageCost, boolean low) {}

    /**
     * Stock of one product across all branches.
     *
     * @param productId     the product
     * @param productName   the product's name
     * @param unit          the product's unit of measure
     * @param totalQuantity units on hand over all branches
     * @param reorderLevel  the configured reorder level, or {@code null} when none is set
     * @param low           true when the product is low (see {@link StockApi#summary})
     * @param branches      the branches that have held the product, ordered by branch id
     */
    record ProductStockView(long productId, String productName, String unit, long totalQuantity, Long reorderLevel,
                            boolean low, List<BranchStockView> branches) {}

    /**
     * A configured reorder level.
     *
     * @param productId   the product
     * @param productName the product's name
     * @param level       units at or below which a branch is low
     * @param updatedBy   id of the user who last set it
     * @param updatedAt   when it was last set
     */
    record ReorderLevelView(long productId, String productName, long level, long updatedBy, Instant updatedAt) {}
}
