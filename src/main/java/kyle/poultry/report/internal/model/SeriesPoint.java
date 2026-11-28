package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A point of the summary series.
 *
 * @param from          first date the point covers
 * @param to            last date the point covers
 * @param netSales      SALE - SALE_REVERSAL
 * @param netCollected  PAYMENT_IN - REFUND
 * @param stockPurchases sum of STOCK_PURCHASE (cash out, not a profit figure); null for assistants
 * @param otherExpenses sum of EXPENSE; null for assistants
 * @param cashMovement  PAYMENT_IN - REFUND - STOCK_PURCHASE - EXPENSE; null for assistants
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesPoint(LocalDate from, LocalDate to, BigDecimal netSales, BigDecimal netCollected,
                          BigDecimal stockPurchases, BigDecimal otherExpenses, BigDecimal cashMovement) {}
