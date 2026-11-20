package kyle.poultry.report;

import java.time.LocalDate;
import kyle.poultry.identity.Actor;
import kyle.poultry.report.DashboardService.Dashboard;
import kyle.poultry.report.ReportService.InventoryValue;
import kyle.poultry.report.ReportService.Profit;
import kyle.poultry.report.ReportService.Summary;
import kyle.poultry.report.SeriesService.Granularity;
import kyle.poultry.report.SeriesService.ProfitSeries;
import kyle.poultry.report.SeriesService.Series;
import kyle.poultry.shared.Csv;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
     * Sales, collections, purchases, expenses and cash movement for a period; open to owner and assistant.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return the summary report
     * @throws kyle.poultry.shared.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/summary")
    Summary summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(from, to);
    }

    /**
     * Profit for a period; owner only, an assistant receives HTTP 403.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return the profit report
     * @throws kyle.poultry.shared.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/profit")
    @PreAuthorize("hasRole('OWNER')")
    Profit profit(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.profit(from, to);
    }

    /**
     * Value of the stock on hand now, per branch and product; open to owner and assistant.
     *
     * @return the inventory valuation
     */
    @GetMapping("/inventory-value")
    InventoryValue inventoryValue() { return reports.inventoryValue(); }

    /**
     * The summary report cut into one point per day or month; open to owner and assistant.
     *
     * @param from        first business date included (ISO yyyy-MM-dd)
     * @param to          last business date included (ISO yyyy-MM-dd)
     * @param granularity DAY (default) or MONTH
     * @return the points and their total, which equals the summary report
     * @throws kyle.poultry.shared.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/series")
    Series series(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return series.series(from, to, granularity);
    }

    /**
     * The profit report cut into one point per day or month; owner only, an assistant receives HTTP 403.
     *
     * @param from        first business date included (ISO yyyy-MM-dd)
     * @param to          last business date included (ISO yyyy-MM-dd)
     * @param granularity DAY (default) or MONTH
     * @return the points and their total, which equals the profit report
     * @throws kyle.poultry.shared.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/profit-series")
    @PreAuthorize("hasRole('OWNER')")
    ProfitSeries profitSeries(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return series.profitSeries(from, to, granularity);
    }

    /**
     * Today's and the month's figures, stock value and low-stock count for the home screen. Profit figures are filled
     * in for the owner only and are {@code null} for an assistant.
     *
     * @param jwt the caller's token, which decides whether profit is included
     * @return the dashboard
     */
    @GetMapping("/dashboard")
    Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) { return dashboard.dashboard(Actor.from(jwt).isOwner()); }

    /**
     * The summary report as a CSV download; open to owner and assistant.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return a {@code text/csv} attachment of {@code metric,value} rows
     */
    @GetMapping("/summary.csv")
    ResponseEntity<byte[]> summaryCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Csv.download("summary-" + from + "-to-" + to + ".csv", ReportCsv.summary(reports.summary(from, to)));
    }

    /**
     * The profit report as a CSV download; owner only.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @return a {@code text/csv} attachment of {@code metric,value} rows
     */
    @GetMapping("/profit.csv")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<byte[]> profitCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Csv.download("profit-" + from + "-to-" + to + ".csv", ReportCsv.profit(reports.profit(from, to)));
    }

    /**
     * The inventory valuation as a CSV download; open to owner and assistant.
     *
     * @return a {@code text/csv} attachment with one row per branch and product, then a TOTAL row
     */
    @GetMapping("/inventory-value.csv")
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
                                     @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return Csv.download("series-" + from + "-to-" + to + ".csv", ReportCsv.series(series.series(from, to, granularity)));
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
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<byte[]> profitSeriesCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "DAY") Granularity granularity) {
        return Csv.download("profit-series-" + from + "-to-" + to + ".csv",
                ReportCsv.profitSeries(series.profitSeries(from, to, granularity)));
    }
}
