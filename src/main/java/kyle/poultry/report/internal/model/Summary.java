package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The summary report. Assistants receive sales and collection figures; cost and cash-movement components are null.
 *
 * @param period         the period covered
 * @param sales          gross, cancelled and net sales
 * @param collections    payments, refunds and retained payments
 * @param stockPurchases cash paid to suppliers (sum of STOCK_PURCHASE); not a profit figure; null for assistants
 * @param otherExpenses  sum of EXPENSE entries; null for assistants
 * @param cashMovement   net cash flow with its per-method breakdown; null for assistants
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Summary(Period period, Sales sales, Collections collections, BigDecimal stockPurchases,
                      BigDecimal otherExpenses, CashMovement cashMovement) {}
