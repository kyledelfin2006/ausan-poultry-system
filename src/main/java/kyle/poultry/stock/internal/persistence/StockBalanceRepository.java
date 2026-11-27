package kyle.poultry.stock.internal.persistence;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockBalance}, keyed by product and branch.
 * <p>
 * Besides reads it holds the two native statements that are the only way balances change:
 * {@link #ensureRow} and {@link #applyDelta}. They are native SQL on purpose so the quantity check and the
 * weighted-average update happen in one database statement. Internal to the stock module: only the stock ledger uses it.
 */
public interface StockBalanceRepository extends JpaRepository<StockBalance, StockBalanceId> {

    /**
     * Finds all balances held at one branch, ordered by product id.
     *
     * @param branchId the branch
     * @return that branch's balances
     */
    List<StockBalance> findByIdBranchIdOrderByIdProductId(long branchId);

    /**
     * Makes sure a row exists so the conditional update below has something to lock.
     * <p>
     * In plain words: insert a balance of zero for this product and branch, and do nothing if the row is
     * already there ({@code on conflict do nothing}). It is safe when two callers do it at the same time:
     * one inserts, the other silently skips. The average cost column takes its default of zero. Pending
     * changes are flushed first and the persistence context is cleared afterwards so no stale balance
     * is read later in the same transaction.
     * <p>
     * <strong>Caution for callers:</strong> {@code clearAutomatically} detaches every entity the current transaction
     * has loaded, not only stock rows. Change your own entities before calling stock, and only read them afterwards;
     * a change made after the call is not written and raises no error. Pinned by {@code DeliveryPersistenceContextTests}.
     *
     * @param productId the product
     * @param branchId  the branch
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into stock_balance (product_id, branch_id, quantity)
            values (:productId, :branchId, 0)
            on conflict do nothing""")
    void ensureRow(@Param("productId") long productId, @Param("branchId") long branchId);

    /**
     * Atomically adds delta unless the result would be negative, and (for incoming stock with a cost)
     * folds that cost into the weighted average. Returns 0 when it refused.
     * One statement = no lost updates, whatever the concurrency. In SQL every right-hand side sees the
     * row's old values, so the average uses the quantity from before this movement.
     * <p>
     * In plain words, the statement finds the balance row for the product and branch and, only if
     * {@code quantity + delta >= 0}, sets the new quantity. When the delta is positive and a cost is given
     * the new average cost is
     * {@code round((oldQuantity * oldAverage + delta * cost) / (oldQuantity + delta), 4)}; otherwise
     * (stock-outs, or incoming stock without a cost) the average is left as it was. The update takes a row
     * lock that is held until the transaction ends, so concurrent movements queue behind each other and the
     * second one sees the first one's result. If the condition fails no row matches and the count is 0.
     * The cost is cast to numeric so a {@code null} parameter has a known type in the {@code is not null} test.
     * <p>
     * <strong>Caution for callers:</strong> {@code clearAutomatically} detaches every entity the current transaction
     * has loaded, not only stock rows. Change your own entities before calling stock, and only read them afterwards;
     * a change made after the call is not written and raises no error. Pinned by {@code DeliveryPersistenceContextTests}.
     *
     * @param productId        the product
     * @param branchId         the branch
     * @param delta            signed units to add (positive) or remove (negative)
     * @param incomingUnitCost cost per unit of incoming stock, or {@code null} to leave the average unchanged
     * @return 1 if the balance was updated, 0 if it was refused because the result would be negative
     *         (or the row does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update stock_balance
               set avg_unit_cost = case
                       when :delta > 0 and cast(:cost as numeric) is not null
                       then round((quantity * avg_unit_cost + :delta * cast(:cost as numeric)) / (quantity + :delta), 4)
                       else avg_unit_cost end,
                   quantity = quantity + :delta
             where product_id = :productId and branch_id = :branchId and quantity + :delta >= 0""")
    int applyDelta(@Param("productId") long productId, @Param("branchId") long branchId,
                   @Param("delta") long delta, @Param("cost") BigDecimal incomingUnitCost);

    /**
     * Reads the weighted average cost of one balance row straight from the database (plain select).
     *
     * @param productId the product
     * @param branchId  the branch
     * @return the average unit cost, or {@code null} if no balance row exists yet
     */
    @Query(nativeQuery = true, value = "select avg_unit_cost from stock_balance where product_id = :productId and branch_id = :branchId")
    BigDecimal averageCost(@Param("productId") long productId, @Param("branchId") long branchId);
}
