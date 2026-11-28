package kyle.poultry.report.internal.model;

import java.util.List;

/**
 * The profit series served to the owner only.
 *
 * @param period      the period covered
 * @param granularity day or month
 * @param points      one point per day or month, oldest first
 * @param totals      the whole period; equals the profit report's figures
 */
public record ProfitSeries(Period period, Granularity granularity, List<ProfitPoint> points, ProfitPoint totals) {}
