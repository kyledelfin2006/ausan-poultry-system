package kyle.poultry.report.internal.model;

import java.math.BigDecimal;

/**
 * Revenue figures for a period.
 *
 * @param gross     sum of SALE entries
 * @param cancelled sum of SALE_REVERSAL entries (sales undone by cancellations)
 * @param net       gross minus cancelled
 */
public record Sales(BigDecimal gross, BigDecimal cancelled, BigDecimal net) {}
