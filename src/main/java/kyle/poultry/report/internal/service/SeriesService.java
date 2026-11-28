package kyle.poultry.report.internal.service;

import kyle.poultry.report.internal.model.Granularity;
import kyle.poultry.report.internal.model.ProfitPoint;
import kyle.poultry.report.internal.model.ProfitSeries;
import kyle.poultry.report.internal.model.Series;
import kyle.poultry.report.internal.model.SeriesPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kyle.poultry.finance.api.model.EntryType;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.report.internal.model.Period;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the report time series: the figures of {@link ReportService#summary} and {@link ReportService#profit} broken
 * into one point per day or per calendar month, for charts. It uses the same definitions as those reports (net sales =
 * SALE - SALE_REVERSAL and so on), so the {@code totals} of a series always equal the matching report for the same
 * period. Every day or month of the period has a point, with zeros where nothing happened, so a chart has no gaps.
 * A month at the edge of the period is cut to the period: its point starts at {@code from} or ends at {@code to}.
 * Read-only; internal to the report module.
 */
@Service
@Transactional(readOnly = true)
public class SeriesService {
    private final FinanceApi finance;

    /**
     * Creates the service.
     *
     * @param finance the finance module's API, the source of the per-day totals
     */
    SeriesService(FinanceApi finance) { this.finance = finance; }
    /**
     * The entry-type totals of one stretch of the period.
     *
     * @param from   first date of the stretch
     * @param to     last date of the stretch
     * @param totals sum per entry type; every type present
     */
    private record Bucket(LocalDate from, LocalDate to, Map<EntryType, BigDecimal> totals) {}

    /**
     * Builds the summary series.
     *
     * @param from        first business date included
     * @param to          last business date included
     * @param granularity day or month
     * @return the series
     * @throws kyle.poultry.shared.error.RuleViolation if a date is missing, {@code from} is after {@code to}, the period
     *                                           exceeds 366 days, or the granularity is missing
     */
    public Series series(LocalDate from, LocalDate to, Granularity granularity) {
        var buckets = buckets(from, to, granularity);
        var points = buckets.stream().map(b -> seriesPoint(b.from(), b.to(), b.totals())).toList();
        // The total is built from the same buckets, so it can never disagree with the points above it.
        return new Series(new Period(from, to), granularity, points, seriesPoint(from, to, sum(buckets)));
    }

    /**
     * Builds the profit series.
     *
     * @param from        first business date included
     * @param to          last business date included
     * @param granularity day or month
     * @return the series
     * @throws kyle.poultry.shared.error.RuleViolation if a date is missing, {@code from} is after {@code to}, the period
     *                                           exceeds 366 days, or the granularity is missing
     */
    public ProfitSeries profitSeries(LocalDate from, LocalDate to, Granularity granularity) {
        var buckets = buckets(from, to, granularity);
        var points = buckets.stream().map(b -> profitPoint(b.from(), b.to(), b.totals())).toList();
        return new ProfitSeries(new Period(from, to), granularity, points, profitPoint(from, to, sum(buckets)));
    }

    /**
     * Cuts the period into stretches and adds the ledger's daily totals into them.
     *
     * @param from        first business date included
     * @param to          last business date included
     * @param granularity day or month
     * @return one bucket per stretch, oldest first; stretches without entries hold zeros
     */
    private List<Bucket> buckets(LocalDate from, LocalDate to, Granularity granularity) {
        ReportService.requirePeriod(from, to);
        if (granularity == null) throw new kyle.poultry.shared.error.RuleViolation("granularity must be DAY or MONTH");

        // Stretch boundaries first; the empty totals are filled below.
        var ends = new ArrayList<LocalDate[]>();
        for (LocalDate start = from; !start.isAfter(to); ) {
            LocalDate end = granularity == Granularity.DAY ? start : YearMonth.from(start).atEndOfMonth();
            if (end.isAfter(to)) end = to;
            ends.add(new LocalDate[] {start, end});
            start = end.plusDays(1);
        }
        var result = new ArrayList<Bucket>();
        // Start date -> index, so a day finds its stretch with one floor lookup.
        var indexByStart = new TreeMap<LocalDate, Integer>();
        for (var e : ends) {
            indexByStart.put(e[0], result.size());
            result.add(new Bucket(e[0], e[1], zeros()));
        }
        // The database returns only days that have entries; add each into the stretch it falls in.
        finance.dailyTotalsByType(from, to).forEach((date, byType) -> {
            var target = result.get(indexByStart.floorEntry(date).getValue()).totals();
            byType.forEach((type, amount) -> target.merge(type, amount, BigDecimal::add));
        });
        return result;
    }

    /**
     * Adds the buckets' totals together.
     *
     * @param buckets the stretches of a period
     * @return the total per entry type for the whole period
     */
    private static Map<EntryType, BigDecimal> sum(List<Bucket> buckets) {
        var total = zeros();
        buckets.forEach(b -> b.totals().forEach((type, amount) -> total.merge(type, amount, BigDecimal::add)));
        return total;
    }

    /** {@return a map with 0.00 for every entry type} */
    private static Map<EntryType, BigDecimal> zeros() {
        Map<EntryType, BigDecimal> zero = new EnumMap<>(EntryType.class);
        for (EntryType t : EntryType.values()) zero.put(t, BigDecimal.ZERO.setScale(2));
        return zero;
    }

    /**
     * Applies the summary definitions to one stretch.
     *
     * @param from first date of the stretch
     * @param to   last date of the stretch
     * @param t    totals per entry type
     * @return the point
     */
    private static SeriesPoint seriesPoint(LocalDate from, LocalDate to, Map<EntryType, BigDecimal> t) {
        BigDecimal collected = t.get(EntryType.PAYMENT_IN).subtract(t.get(EntryType.REFUND));
        BigDecimal purchases = t.get(EntryType.STOCK_PURCHASE), expenses = t.get(EntryType.EXPENSE);
        return new SeriesPoint(from, to, t.get(EntryType.SALE).subtract(t.get(EntryType.SALE_REVERSAL)), collected,
                purchases, expenses, collected.subtract(purchases).subtract(expenses));
    }

    /**
     * Applies the profit definitions to one stretch.
     *
     * @param from first date of the stretch
     * @param to   last date of the stretch
     * @param t    totals per entry type
     * @return the point
     */
    private static ProfitPoint profitPoint(LocalDate from, LocalDate to, Map<EntryType, BigDecimal> t) {
        BigDecimal netSales = t.get(EntryType.SALE).subtract(t.get(EntryType.SALE_REVERSAL));
        BigDecimal cogs = t.get(EntryType.COST_OF_GOODS).subtract(t.get(EntryType.COGS_REVERSAL));
        BigDecimal gross = netSales.subtract(cogs);
        // STOCK_PURCHASE is deliberately not read: its cost reaches profit when the stock is delivered.
        return new ProfitPoint(from, to, netSales, cogs, gross, t.get(EntryType.EXPENSE),
                gross.subtract(t.get(EntryType.EXPENSE)));
    }
}
