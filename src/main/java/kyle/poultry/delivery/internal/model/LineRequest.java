package kyle.poultry.delivery.internal.model;

import java.math.BigDecimal;

/**
 * One package line of a new delivery.
 *
 * @param packageId the sales package being sold
 * @param quantity  how many packages; must be positive
 * @param unitPrice price override for this delivery only, or null to use the package price
 */
public record LineRequest(long packageId, long quantity, BigDecimal unitPrice) {}
