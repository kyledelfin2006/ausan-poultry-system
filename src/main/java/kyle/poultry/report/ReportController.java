package kyle.poultry.report;

import java.time.LocalDate;
import java.util.List;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.identity.api.security.RequiresCapability;
import kyle.poultry.report.DashboardService.Dashboard;
import kyle.poultry.report.ReportService.InventoryValue;
import kyle.poultry.report.ReportService.Profit;
import kyle.poultry.report.ReportService.Summary;
import kyle.poultry.report.SeriesService.Granularity;
import kyle.poultry.report.SeriesService.ProfitSeries;
import kyle.poultry.report.SeriesService.Series;
import kyle.poultry.report.SeriesService.SeriesPoint;
import kyle.poultry.shared.csv.Csv;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP face of the report module: read-only period reports under {@code /api/reports}. Every request is
 * authenticated by the global JWT rule; the profit report is further restricted to the owner. Errors such as a bad
 * period are turned into {@code ProblemDetail} responses by the shared exception handler.
 */
@RestController
@RequestMapping("/api/reports")
class ReportController {
    private final ReportService reports;
    private final SeriesService series;
    private final DashboardService dashboard;

    /**
     * Creates the controller.
     *
     * @param reports   the service that computes the reports
     * @param series    the service that cuts them into days or months
     * @param dashboard the service behind the home-screen figures
     */
    ReportController(ReportService reports, SeriesService series, DashboardService dashboard) {
        this.reports = reports; this.series = series; this.dashboard = dashboard;
    }

    /**
     * Sales and collections are open to both roles. Cost and cash-flow fields are omitted for assistants.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return the summary report
     * @throws kyle.poultry.shared.error.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/summary")
    Summary summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @AuthenticationPrincipal Jwt jwt) {
        return visibleSummary(reports.summary(from, to), Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT));
    }

    /**
     * Profit for a period; owner only, an assistant receives HTTP 403.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return the profit report
     * @throws kyle.poultry.shared.error.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/profit")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    Profit profit(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.profit(from, to);
    }

    /**
     * Value of the stock on hand now, per branch and product; owner only.
     *
     * @return the inventory valuation
     */
    @GetMapping("/inventory-value")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    InventoryValue inventoryValue() { return reports.inventoryValue(); }

    /**
     * The summary report cut into one point per day or month; cost and cash-flow fields are omitted for assistants.
     *
     * @param from        first business date included (ISO yyyy-MM-dd)
     * @param to          last business date included (ISO yyyy-MM-dd)
     * @param granularity DAY (default) or MONTH
     * @return the points and their total, which equals the summary report
     * @throws kyle.poultry.shared.error.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/series")
    Series series(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @RequestParam(defaultValue = "DAY") Granularity granularity,
                  @AuthenticationPrincipal Jwt jwt) {
        return visibleSeries(series.series(from, to, granularity), Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT));
    }

    /**
     * The profit report cut into one point per day or month; owner only, an assistant receives HTTP 403.
     *
     * @param from        first business date included (ISO yyyy-MM-dd)
     * @param to          last business date included (ISO yyyy-MM-dd)
     * @param granularity DAY (default) or MONTH
     * @return the points and their total, which equals the profit report
     * @throws kyle.poultry.shared.error.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/profit-series")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    ProfitSeries profitSeries(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return series.profitSeries(from, to, granularity);
    }

    /**
     * Today's and the month's safe sales and collection figures and low-stock count. Cost, cash-flow, stock-value and
     * profit fields are owner-only and omitted for assistants.
     *
     * @param jwt the caller's token, which decides which fields are included
     * @return the dashboard
     */
    @GetMapping("/dashboard")
    Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) { return dashboard.dashboard(Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT)); }

    /**
     * The summary report as a CSV download; open to owner and assistant.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return a {@code text/csv} attachment of {@code metric,value} rows
     */
    @GetMapping("/summary.csv")
    ResponseEntity<byte[]> summaryCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @AuthenticationPrincipal Jwt jwt) {
        return Csv.download("summary-" + from + "-to-" + to + ".csv",
                ReportCsv.summary(visibleSummary(reports.summary(from, to), Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT))));
    }

    /**
     * The profit report as a CSV download; owner only.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return a {@code text/csv} attachment of {@code metric,value} rows
     */
    @GetMapping("/profit.csv")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    ResponseEntity<byte[]> profitCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Csv.download("profit-" + from + "-to-" + to + ".csv", ReportCsv.profit(reports.profit(from, to)));
    }

    /**
     * The inventory valuation as a CSV download; owner only.
     *
     * @return a {@code text/csv} attachment with one row per branch and product, then a TOTAL row
     */
    @GetMapping("/inventory-value.csv")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    ResponseEntity<byte[]> inventoryValueCsv() {
        return Csv.download("inventory-value.csv", ReportCsv.inventoryValue(reports.inventoryValue()));
    }

    /**
     * The summary series as a CSV download; open to owner and assistant.
     *
     * @param from        first business date included (ISO yyyy-MM-dd)
     * @param to          last business date included (ISO yyyy-MM-dd)
     * @param granularity DAY (default) or MONTH
     * @return a {@code text/csv} attachment with one row per point, then a TOTAL row
     */
    @GetMapping("/series.csv")
    ResponseEntity<byte[]> seriesCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(defaultValue = "DAY") Granularity granularity,
                                     @AuthenticationPrincipal Jwt jwt) {
        return Csv.download("series-" + from + "-to-" + to + ".csv",
                ReportCsv.series(visibleSeries(series.series(from, to, granularity), Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT))));
    }

    /**
     * The profit series as a CSV download; owner only.
     *
     * @param from        first business date included (ISO yyyy-MM-dd)
     * @param to          last business date included (ISO yyyy-MM-dd)
     * @param granularity DAY (default) or MONTH
     * @return a {@code text/csv} attachment with one row per point, then a TOTAL row
     */
    @GetMapping("/profit-series.csv")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    ResponseEntity<byte[]> profitSeriesCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return Csv.download("profit-series-" + from + "-to-" + to + ".csv",
                ReportCsv.profitSeries(series.profitSeries(from, to, granularity)));
    }

    private static Summary visibleSummary(Summary summary, boolean owner) {
        return owner ? summary : new Summary(summary.period(), summary.sales(), summary.collections(), null, null, null);
    }

    private static Series visibleSeries(Series result, boolean owner) {
        if (owner) return result;
        List<SeriesPoint> points = result.points().stream().map(ReportController::withoutCosts).toList();
        return new Series(result.period(), result.granularity(), points, withoutCosts(result.totals()));
    }

    private static SeriesPoint withoutCosts(SeriesPoint point) {
        return new SeriesPoint(point.from(), point.to(), point.netSales(), point.netCollected(), null, null, null);
    }
}
