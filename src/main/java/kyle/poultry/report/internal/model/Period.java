package kyle.poultry.report.internal.model;

import java.time.LocalDate;

/**
 * The inclusive date range a report covers.
 *
 * @param from first business date included
 * @param to   last business date included
 */
public record Period(LocalDate from, LocalDate to) {}
