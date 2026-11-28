package kyle.poultry.stock.internal.persistence;

import kyle.poultry.stock.api.model.ReorderLevelView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The reorder level of one product: the quantity at or below which a branch counts as low on it. Table
 * {@code stock_reorder_level}, one row per product. Unlike the ledgers it is plain configuration and is updated in
 * place. Internal to the stock module; other modules see it through {@code ReorderLevelView}.
 */
@Entity
@Table(name = "stock_reorder_level")
public class ReorderLevel {
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
    public ReorderLevel(long productId, long reorderLevel, long updatedBy, Instant updatedAt) {
        this.productId = productId; this.reorderLevel = reorderLevel; this.updatedBy = updatedBy; this.updatedAt = updatedAt;
    }

    /**
     * Replaces the level.
     *
     * @param reorderLevel the new level (not negative)
     * @param updatedBy    id of the user changing it
     * @param updatedAt    when it is changed
     */
    public void change(long reorderLevel, long updatedBy, Instant updatedAt) {
        this.reorderLevel = reorderLevel; this.updatedBy = updatedBy; this.updatedAt = updatedAt;
    }

    /** {@return the product id} */
    public long productId() { return productId; }
    /** {@return the level in units} */
    public long level() { return reorderLevel; }
    /** {@return the id of the user who last set it} */
    public long updatedBy() { return updatedBy; }
    /** {@return when it was last set} */
    public Instant updatedAt() { return updatedAt; }
}
