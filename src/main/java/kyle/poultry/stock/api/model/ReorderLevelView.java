package kyle.poultry.stock.api.model;

import java.time.Instant;

/**
 * A configured reorder level.
 *
 * @param productId   the product
 * @param productName the product's name
 * @param level       units at or below which a branch is low
 * @param updatedBy   id of the user who last set it
 * @param updatedAt   when it was last set
 */
public record ReorderLevelView(long productId, String productName, long level, long updatedBy, Instant updatedAt) {}
