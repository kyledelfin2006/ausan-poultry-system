package kyle.poultry.report.internal.model;

import java.math.BigDecimal;

/**
 * The profit report served to the owner only.
 *
 * @param period        the period covered
 * @param netSales      SALE - SALE_REVERSAL
 * @param costOfGoods   cost of the stock delivered
 * @param grossProfit   net sales minus net cost of goods
 * @param otherExpenses sum of EXPENSE entries
 * @param profit        gross profit minus other expenses; stock purchases are never included
 */
public record Profit(Period period, BigDecimal netSales, CostOfGoods costOfGoods, BigDecimal grossProfit,
                     BigDecimal otherExpenses, BigDecimal profit) {}
