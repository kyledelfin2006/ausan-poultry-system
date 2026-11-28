package kyle.poultry.report.internal.model;

import java.util.List;

/**
 * The summary series served to both roles.
 *
 * @param period      the period covered
 * @param granularity day or month
 * @param points      one point per day or month, oldest first
 * @param totals      the whole period; equals the summary report's figures
 */
public record Series(Period period, Granularity granularity, List<SeriesPoint> points, SeriesPoint totals) {}
