package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stock value of one branch.
 *
 * @param branchId the branch (names belong to the catalog module, which this module does not use)
 * @param lines    its products with stock, ordered by product name
 * @param total    sum of the line values
 */
public record BranchInventory(long branchId, List<InventoryLine> lines, BigDecimal total) {}
