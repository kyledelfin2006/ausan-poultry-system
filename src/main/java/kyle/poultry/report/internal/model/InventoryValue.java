package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inventory valuation across all branches, as of now.
 *
 * @param branches   branches that hold stock, ordered by branch id
 * @param grandTotal sum of the branch totals
 */
public record InventoryValue(List<BranchInventory> branches, BigDecimal grandTotal) {}
