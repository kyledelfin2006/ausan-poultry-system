package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The figures of one period shown on the dashboard.
 *
 * @param from           first business date
 * @param to             last business date
 * @param netSales       SALE - SALE_REVERSAL
 * @param netCollected   PAYMENT_IN - REFUND
 * @param stockPurchases cash paid to suppliers; {@code null} for an assistant
 * @param otherExpenses  EXPENSE; {@code null} for an assistant
 * @param cashMovement   net cash flow; {@code null} for an assistant
 * @param profit         the period's profit; {@code null} for an assistant
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Figures(LocalDate from, LocalDate to, BigDecimal netSales, BigDecimal netCollected, BigDecimal stockPurchases,
                      BigDecimal otherExpenses, BigDecimal cashMovement, BigDecimal profit) {}
