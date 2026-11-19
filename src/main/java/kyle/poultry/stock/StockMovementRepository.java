package kyle.poultry.stock;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @Query("""
            select m from StockMovement m
             where m.productId = :productId and m.branchId = :branchId
             order by m.occurredOn desc, m.id desc""")
    Page<StockMovement> history(@Param("productId") long productId, @Param("branchId") long branchId, Pageable page);

    List<StockMovement> findBySourceTypeAndSourceIdAndType(String sourceType, long sourceId, MovementType type);

    boolean existsByReversesId(long reversesId);

    @Query("select coalesce(sum(m.quantityDelta), 0) from StockMovement m where m.productId = :productId and m.branchId = :branchId")
    long sumDelta(@Param("productId") long productId, @Param("branchId") long branchId);
}
