package kyle.poultry.stock;

import kyle.poultry.shared.ConflictException;

public class InsufficientStockException extends ConflictException {
    public InsufficientStockException(long productId, long branchId) {
        super("Insufficient stock for product " + productId + " at branch " + branchId);
    }
}
