package kyle.poultry.delivery.internal.model;

/**
 * Outcome of a create call.
 *
 * @param delivery the delivery (new, or the earlier one for a repeated idempotency key)
 * @param created  false when an earlier request with the same idempotency key already created {@code delivery}
 */
public record CreateResult(DeliveryView delivery, boolean created) {}
