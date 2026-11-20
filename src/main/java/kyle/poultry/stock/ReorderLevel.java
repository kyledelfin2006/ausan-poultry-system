package kyle.poultry.stock;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The reorder level of one product: the quantity at or below which a branch counts as low on it. Table
 * {@code stock_reorder_level}, one row per product. Unlike the ledgers it is plain configuration and is updated in
 * place. Package-private; other modules see it through {@link StockApi.ReorderLevelView}.
 */
@Entity
@Table(name = "stock_reorder_level")
class ReorderLevel {
    /** The product; also the primary key, so a product has at most one level. */
    @Id private Long productId;
    /** Units at or below which a branch is low; never negative. */
    private long reorderLevel;
    /** Id of the user who last set it. */
    private long updatedBy;
    /** When it was last set. */
    private Instant updatedAt;

    /** Required by JPA; not for application code. */
    protected ReorderLevel() {}

    /**
     * Creates a level for a product.
     *
     * @param productId    the product
     * @param reorderLevel units at or below which a branch is low (not negative)
     * @param updatedBy    id of the user setting it
     * @param updatedAt    when it is set
     */
    ReorderLevel(long productId, long reorderLevel, long updatedBy, Instant updatedAt) {
        this.productId = productId; this.reorderLevel = reorderLevel; this.updatedBy = updatedBy; this.updatedAt = updatedAt;
    }

    /**
     * Replaces the level.
     *
     * @param reorderLevel the new level (not negative)
     * @param updatedBy    id of the user changing it
     * @param updatedAt    when it is changed
     */
    void change(long reorderLevel, long updatedBy, Instant updatedAt) {
        this.reorderLevel = reorderLevel; this.updatedBy = updatedBy; this.updatedAt = updatedAt;
    }

    /** {@return the product id} */
    long productId() { return productId; }
    /** {@return the level in units} */
    long level() { return reorderLevel; }
    /** {@return the id of the user who last set it} */
    long updatedBy() { return updatedBy; }
    /** {@return when it was last set} */
    Instant updatedAt() { return updatedAt; }
}
