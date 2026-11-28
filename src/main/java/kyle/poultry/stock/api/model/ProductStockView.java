package kyle.poultry.stock.api.model;

import java.util.List;

/**
 * Stock of one product across all branches.
 *
 * @param productId     the product
 * @param productName   the product's name
 * @param unit          the product's unit of measure
 * @param totalQuantity units on hand over all branches
 * @param reorderLevel  the configured reorder level, or {@code null} when none is set
 * @param low           true when the product is low (see {@link kyle.poultry.stock.api.StockApi#summary})
 * @param branches      the branches that have held the product, ordered by branch id
 */
public record ProductStockView(long productId, String productName, String unit, long totalQuantity, Long reorderLevel,
                               boolean low, List<BranchStockView> branches) {}
