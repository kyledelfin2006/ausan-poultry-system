package kyle.poultry.report.internal.model;

/** How finely a period is cut into points. */
public enum Granularity {
    /** One point per business date: at most 366 points. */
    DAY,
    /** One point per calendar month: at most 13 points; edge months are cut to the period. */
    MONTH
}
