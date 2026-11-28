package kyle.poultry.report.internal.model;

import java.math.BigDecimal;

/**
 * Money received from and returned to customers.
 *
 * @param received                      sum of PAYMENT_IN entries in the period
 * @param refunds                       sum of REFUND entries in the period
 * @param netCollected                  received minus refunds
 * @param retainedOnCancelledDeliveries money held, as of the period end, for cancelled sources and not yet
 *                                      refunded; reported on its own and never part of sales
 */
public record Collections(BigDecimal received, BigDecimal refunds, BigDecimal netCollected,
                          BigDecimal retainedOnCancelledDeliveries) {}
