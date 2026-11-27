package kyle.poultry.stock.internal.persistence;

import kyle.poultry.stock.api.model.MovementType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the append-only {@link StockMovement} ledger.
 * <p>
 * Only inserts and reads are used; movements are never updated or deleted. Internal to the stock module: only the stock
 * module uses it.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * Pages through the movements of one product at one branch, newest first.
     * <p>
     * In plain words: select the movements for the product and branch, ordered by business date descending
     * and then by id descending, so entries on the same day appear in the order they were recorded.
     *
     * @param productId the product
     * @param branchId  the branch
     * @param page      the page request
     * @return the requested page of movements
     */
    @Query("""
            select m from StockMovement m
             where m.productId = :productId and m.branchId = :branchId
             order by m.occurredOn desc, m.id desc""")
    Page<StockMovement> history(@Param("productId") long productId, @Param("branchId") long branchId, Pageable page);

    /**
     * Finds the movements of a given type caused by one source record (for example a delivery's stock-outs).
     *
     * @param sourceType the kind of source record
     * @param sourceId   the id of the source record
     * @param type       the movement type to match
     * @return the matching movements, possibly empty
     */
    List<StockMovement> findBySourceTypeAndSourceIdAndType(String sourceType, long sourceId, MovementType type);

    /**
     * Finds every movement caused by one source record, oldest first.
     *
     * @param sourceType the kind of source record
     * @param sourceId   the id of the source record
     * @return the movements ordered by id ascending, possibly empty
     */
    List<StockMovement> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, long sourceId);

    /**
     * Tells whether a movement has already been reversed by another movement.
     *
     * @param reversesId the id of the original movement
     * @return {@code true} if a reversal pointing at it exists
     */
    boolean existsByReversesId(long reversesId);

    /**
     * Adds up every signed quantity for one product at one branch; the result should equal the balance.
     * <p>
     * In plain words: sum the quantity changes of all matching movements, treating "no movements" as 0
     * instead of null. It is a read-only check (currently not called by production code).
     *
     * @param productId the product
     * @param branchId  the branch
     * @return the net quantity of all recorded movements
     */
    @Query("select coalesce(sum(m.quantityDelta), 0) from StockMovement m where m.productId = :productId and m.branchId = :branchId")
    long sumDelta(@Param("productId") long productId, @Param("branchId") long branchId);
}
