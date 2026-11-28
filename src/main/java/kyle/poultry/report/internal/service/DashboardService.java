package kyle.poultry.report.internal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import kyle.poultry.finance.api.model.EntryType;
import kyle.poultry.stock.api.StockApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the home-screen dashboard: today's and the month's figures, the value of the stock and how many products are
 * low. Every figure is computed by {@link ReportService}, so the dashboard can never disagree with the reports it
 * summarises. "Today" and "this month" are in the farm's zone, taken from the injected {@link Clock}. Read-only;
 * internal to the report module.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {
    private final ReportService reports;
    private final StockApi stock;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param reports the report calculations the dashboard reuses
     * @param stock   source of the low-stock count
     * @param clock   the application clock, which fixes what "today" is
     */
    DashboardService(ReportService reports, StockApi stock, Clock clock) {
        this.reports = reports; this.stock = stock; this.clock = clock;
    }

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

    /**
     * The dashboard.
     *
     * @param asOf                          today's business date
     * @param today                         figures for today
     * @param monthToDate                   figures from the first of this month to today
     * @param inventoryValue                value of all stock on hand now (quantity x average cost); {@code null} for an assistant
     * @param lowStockProducts              how many products are low (see {@link StockApi#summary(boolean)})
     * @param retainedOnCancelledDeliveries money still held for cancelled deliveries as of today
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dashboard(LocalDate asOf, Figures today, Figures monthToDate, BigDecimal inventoryValue, int lowStockProducts,
                     BigDecimal retainedOnCancelledDeliveries) {}

    /**
     * Builds the dashboard for the current moment.
     *
     * @param owner true for the owner; an assistant gets no cost, cash flow, inventory value, or profit figures
     * @return the dashboard
     */
    public Dashboard dashboard(boolean owner) {
        LocalDate today = LocalDate.now(clock);
        // Month to date always includes today, even on the first of the month (one day).
        LocalDate monthStart = today.withDayOfMonth(1);
        // PERF-3: one totals query per period (shared by summary and profit) and one retained-money scan, because both
        // periods end today and so measure the same amount. Only the month figure is shown.
        var todayTotals = reports.totals(today, today);
        var monthTotals = reports.totals(monthStart, today);
        BigDecimal retained = reports.retainedOnCancelled(today);
        var todaySummary = reports.summary(today, today, todayTotals, retained);
        var monthSummary = reports.summary(monthStart, today, monthTotals, retained);
        return new Dashboard(today, figures(todaySummary, todayTotals, owner), figures(monthSummary, monthTotals, owner),
                owner ? reports.inventoryValue().grandTotal() : null, stock.summary(true).size(), retained);
    }

    /**
     * Turns a summary into dashboard figures, adding the profit for the owner.
     *
     * @param s             the period's summary report
     * @param totals        the period's per-type totals, already read for the summary
     * @param owner whether to include owner-only financial figures
     * @return the figures
     */
    private Figures figures(ReportService.Summary s, Map<EntryType, BigDecimal> totals, boolean owner) {
        var p = s.period();
        BigDecimal profit = owner ? reports.profit(p.from(), p.to(), totals).profit() : null;
        return new Figures(p.from(), p.to(), s.sales().net(), s.collections().netCollected(),
                owner ? s.stockPurchases() : null, owner ? s.otherExpenses() : null,
                owner ? s.cashMovement().total() : null, profit);
    }
}
