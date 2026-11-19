package kyle.poultry.report;

import java.time.LocalDate;
import kyle.poultry.report.ReportService.InventoryValue;
import kyle.poultry.report.ReportService.Profit;
import kyle.poultry.report.ReportService.Summary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * Creates the controller.
     *
     * @param reports the service that computes the reports
     */
    ReportController(ReportService reports) { this.reports = reports; }

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
}
