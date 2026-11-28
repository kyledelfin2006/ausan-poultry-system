package kyle.poultry.report.internal.model;

import java.math.BigDecimal;

/**
 * Cost of the stock that was delivered in a period.
 *
 * @param delivered sum of COST_OF_GOODS entries
 * @param reversed  sum of COGS_REVERSAL entries (cost undone by cancellations)
 * @param net       delivered minus reversed
 */
public record CostOfGoods(BigDecimal delivered, BigDecimal reversed, BigDecimal net) {}
