package kyle.poultry.stock.api.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import kyle.poultry.finance.api.model.PaymentMethod;

/**
 * Input for a manual movement.
 *
 * @param productId     the product affected
 * @param branchId      the branch affected
 * @param type          the movement type, which decides the legal sign of the quantity
 * @param quantityDelta signed units: positive adds stock, negative removes it, zero is never allowed
 * @param unitCost      required for PURCHASE; OPENING and DONATION default to zero
 * @param reason        free text, required for USAGE and ADJUSTMENT
 * @param occurredOn    the business date; assistants may backdate at most 7 days, owners may use any past date
 * @param paymentMethod required for PURCHASE (how the supplier was paid)
 */
public record StockCommand(long productId, long branchId, MovementType type, long quantityDelta,
                           BigDecimal unitCost, String reason, LocalDate occurredOn, PaymentMethod paymentMethod) {}
