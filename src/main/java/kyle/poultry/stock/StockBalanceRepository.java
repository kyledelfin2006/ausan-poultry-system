package kyle.poultry.stock;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StockBalanceRepository extends JpaRepository<StockBalance, StockBalanceId> {

    List<StockBalance> findByIdBranchIdOrderByIdProductId(long branchId);

    /** Makes sure a row exists so the conditional update below has something to lock. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into stock_balance (product_id, branch_id, quantity)
            values (:productId, :branchId, 0)
            on conflict do nothing""")
    void ensureRow(@Param("productId") long productId, @Param("branchId") long branchId);

    /**
     * Atomically adds delta unless the result would be negative. Returns 0 when it refused.
     * One statement = no lost updates, whatever the concurrency.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update stock_balance
               set quantity = quantity + :delta
             where product_id = :productId and branch_id = :branchId and quantity + :delta >= 0""")
    int applyDelta(@Param("productId") long productId, @Param("branchId") long branchId, @Param("delta") long delta);
}
