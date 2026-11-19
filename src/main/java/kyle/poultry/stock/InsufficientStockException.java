package kyle.poultry.stock;

import kyle.poultry.shared.ConflictException;

/**
 * A stock-out was refused because the branch holds fewer units than requested (maps to HTTP 409).
 * <p>
 * Thrown by the stock module; callers such as the delivery flow let it propagate so their whole
 * transaction rolls back and no partial stock-out is left behind.
 */
public class InsufficientStockException extends ConflictException {

    /**
     * Creates the exception with a message naming the product and branch.
     *
     * @param productId the product that ran short
     * @param branchId  the branch that does not hold enough of it
     */
    public InsufficientStockException(long productId, long branchId) {
        super("Insufficient stock for product " + productId + " at branch " + branchId);
    }
}
