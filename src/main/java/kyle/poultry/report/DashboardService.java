package kyle.poultry.report;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import kyle.poultry.stock.StockApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the home-screen dashboard: today's and the month's figures, the value of the stock and how many products are
 * low. Every figure is computed by {@link ReportService}, so the dashboard can never disagree with the reports it
 * summarises. "Today" and "this month" are in the farm's zone, taken from the injected {@link Clock}. Read-only;
 * package-private.
 */
@Service
@Transactional(readOnly = true)
class DashboardService {
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
     * @param stockPurchases cash paid to suppliers
     * @param otherExpenses  EXPENSE
     * @param cashMovement   net cash flow
     * @param profit         the period's profit, or {@code null} for an assistant (profit is owner-only)
     */
    record Figures(LocalDate from, LocalDate to, BigDecimal netSales, BigDecimal netCollected, BigDecimal stockPurchases,
                   BigDecimal otherExpenses, BigDecimal cashMovement, BigDecimal profit) {}

    /**
     * The dashboard.
     *
     * @param asOf                          today's business date
     * @param today                         figures for today
     * @param monthToDate                   figures from the first of this month to today
     * @param inventoryValue                value of all stock on hand now (quantity x average cost)
     * @param lowStockProducts              how many products are low (see {@link StockApi#summary(boolean)})
     * @param retainedOnCancelledDeliveries money still held for cancelled deliveries as of today
     */
    record Dashboard(LocalDate asOf, Figures today, Figures monthToDate, BigDecimal inventoryValue, int lowStockProducts,
                     BigDecimal retainedOnCancelledDeliveries) {}

    /**
     * Builds the dashboard for the current moment.
     *
     * @param includeProfit true for the owner; an assistant gets {@code null} profit figures
     * @return the dashboard
     */
    Dashboard dashboard(boolean includeProfit) {
        LocalDate today = LocalDate.now(clock);
        // Month to date always includes today, even on the first of the month (one day).
        LocalDate monthStart = today.withDayOfMonth(1);
        var todaySummary = reports.summary(today, today);
        var monthSummary = reports.summary(monthStart, today);
        return new Dashboard(today, figures(todaySummary, includeProfit), figures(monthSummary, includeProfit),
                reports.inventoryValue().grandTotal(), stock.summary(true).size(),
                monthSummary.collections().retainedOnCancelledDeliveries());
    }

    /**
     * Turns a summary into dashboard figures, adding the profit for the owner.
     *
     * @param s             the period's summary report
     * @param includeProfit whether to calculate profit (owner only)
     * @return the figures
     */
    private Figures figures(ReportService.Summary s, boolean includeProfit) {
        var p = s.period();
        BigDecimal profit = includeProfit ? reports.profit(p.from(), p.to()).profit() : null;
        return new Figures(p.from(), p.to(), s.sales().net(), s.collections().netCollected(), s.stockPurchases(),
                s.otherExpenses(), s.cashMovement().total(), profit);
    }
}
