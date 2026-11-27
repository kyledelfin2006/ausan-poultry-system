package kyle.poultry.stock.api;

import kyle.poultry.stock.api.model.BalanceView;
import kyle.poultry.stock.api.model.MovementView;
import kyle.poultry.stock.api.model.ProductStockView;
import kyle.poultry.stock.api.model.ReorderLevelView;
import kyle.poultry.stock.api.model.StockCommand;
import kyle.poultry.stock.api.model.StockLine;
import kyle.poultry.stock.api.model.MovementType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.shared.reference.SourceRef;
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
 *   <li><b>Failures:</b> {@link kyle.poultry.shared.error.NotFoundException} (404) for unknown products or branches,
 *       {@link kyle.poultry.shared.error.RuleViolation} (422) for inactive ones or broken input rules,
 *       {@code InsufficientStockException} (409) for shortages.</li>
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
     * @throws kyle.poultry.shared.error.RuleViolation if the type is not manual, the date is missing or outside the
     *         actor's allowed range, the sign or cost is wrong for the type, a reason or payment method is missing,
     *         or the product or branch is inactive
     * @throws org.springframework.security.access.AccessDeniedException if a non-owner records opening
     *         stock or an adjustment
     * @throws InsufficientStockException if a negative quantity exceeds what the branch holds
     * @throws kyle.poultry.shared.error.NotFoundException if the product or branch does not exist
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
     * @throws kyle.poultry.shared.error.RuleViolation if a quantity is not positive or a product or branch is inactive
     * @throws kyle.poultry.shared.error.NotFoundException if a product or branch does not exist
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
     * @throws kyle.poultry.shared.error.RuleViolation                         if the level is negative or the product is inactive
     * @throws kyle.poultry.shared.error.NotFoundException                     if the product does not exist
     */
    ReorderLevelView setReorderLevel(long productId, long level, Actor actor);

    /**
     * Removes the reorder level of a product, so it is never flagged low. Owner only.
     *
     * @param productId the product
     * @param actor     who removes it; must be the owner
     * @throws org.springframework.security.access.AccessDeniedException if the actor is not the owner
     * @throws kyle.poultry.shared.error.NotFoundException                     if the product has no level
     */
    void clearReorderLevel(long productId, Actor actor);
}
