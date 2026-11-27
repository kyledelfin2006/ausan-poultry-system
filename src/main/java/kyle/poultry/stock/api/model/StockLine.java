package kyle.poultry.stock.api.model;

/**
 * One line of a stock-out request.
 *
 * @param productId the product to remove
 * @param quantity  the number of units to remove (positive)
 */
public record StockLine(long productId, long quantity) {}
