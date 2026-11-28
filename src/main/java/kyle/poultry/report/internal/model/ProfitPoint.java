package kyle.poultry.report.internal.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A point of the profit series.
 *
 * @param from          first date the point covers
 * @param to            last date the point covers
 * @param netSales      SALE - SALE_REVERSAL
 * @param costOfGoods   COST_OF_GOODS - COGS_REVERSAL
 * @param grossProfit   net sales minus cost of goods
 * @param otherExpenses sum of EXPENSE
 * @param profit        gross profit minus other expenses; stock purchases are never included
 */
public record ProfitPoint(LocalDate from, LocalDate to, BigDecimal netSales, BigDecimal costOfGoods, BigDecimal grossProfit,
                          BigDecimal otherExpenses, BigDecimal profit) {}
