package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.util.Map;
import kyle.poultry.finance.api.model.PaymentMethod;

/**
 * Net cash flow of a period.
 *
 * @param total    payments received - refunds - stock purchases - other expenses
 * @param byMethod the same net figure per payment method; every method is present
 */
public record CashMovement(BigDecimal total, Map<PaymentMethod, BigDecimal> byMethod) {}
