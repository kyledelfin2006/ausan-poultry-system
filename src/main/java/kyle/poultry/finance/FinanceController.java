package kyle.poultry.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import kyle.poultry.finance.FinanceApi.EntryView;
import kyle.poultry.identity.Actor;
import kyle.poultry.shared.Csv;
import kyle.poultry.shared.RuleViolation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP face of the finance module under {@code /api/finance}: record expenses and browse ledger entries. It is
 * package-private and used by authenticated owners and assistants (JWT is required globally); money rules
 * live in {@link FinanceApi}, and violations become HTTP 422 through the shared exception handler.
 */
@RestController
@RequestMapping("/api/finance")
class FinanceController {
    /** Longest period one CSV export may cover; one full leap year, like the reports. */
    private static final int MAX_EXPORT_DAYS = 366;

    private final FinanceApi finance;

    /**
     * Creates the controller.
     *
     * @param finance the ledger used for every operation
     */
    FinanceController(FinanceApi finance) { this.finance = finance; }

    /**
     * Request body for recording an expense.
     *
     * @param amount      cash paid, at least 0.01, at most 12 integer and 2 fraction digits
     * @param method      how it was paid
     * @param occurredOn  business date of the expense
     * @param description what the money was for, at most 255 characters
     */
    record RecordExpense(@NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                         @NotNull PaymentMethod method,
                         @NotNull LocalDate occurredOn,
                         @NotBlank @Size(max = 255) String description) {}

    /**
     * Records an operating expense for the current user.
     *
     * @param r   the validated request body
     * @param jwt the caller's token, from which the acting user and role are read
     * @return the stored entry (HTTP 201)
     * @throws kyle.poultry.shared.RuleViolation if the date is in the future or the amount is invalid (HTTP 422)
     */
    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    EntryView expense(@Valid @RequestBody RecordExpense r, @AuthenticationPrincipal Jwt jwt) {
        return finance.recordExpense(r.amount(), r.method(), r.occurredOn(), r.description(), Actor.from(jwt));
    }

    /**
     * Lists ledger entries for an inclusive period, newest first; this is the drill-down behind report figures.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @param type optional filter on one entry type
     * @param page paging parameters from the query string
     * @return a page of entries
     * @throws kyle.poultry.shared.RuleViolation if from is after to (HTTP 422)
     */
    @GetMapping("/entries")
    Page<EntryView> entries(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) EntryType type,
                            Pageable page, @AuthenticationPrincipal Jwt jwt) {
        return finance.entries(from, to, type, page, Actor.from(jwt));
    }

    /**
     * Downloads every ledger entry of an inclusive period as CSV, newest first (the same order and filter as
     * {@link #entries}, without paging). The file is read with a single query, so it is a consistent snapshot even while
     * entries are being recorded. The period may span at most 366 days, so a download stays bounded.
     *
     * @param from first business date included (ISO yyyy-MM-dd)
     * @param to   last business date included (ISO yyyy-MM-dd)
     * @param type optional filter on one entry type
     * @return a {@code text/csv} attachment with one header row and one row per entry
     * @throws kyle.poultry.shared.RuleViolation if from is after to or the period exceeds 366 days (HTTP 422)
     */
    @GetMapping("/entries.csv")
    ResponseEntity<byte[]> entriesCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) EntryType type,
                                      @AuthenticationPrincipal Jwt jwt) {
        // A from/to that is missing or backwards is rejected by the ledger; only the length limit is added here.
        if (from.isBefore(to.minusDays(MAX_EXPORT_DAYS - 1L)))
            throw new RuleViolation("An export period cannot exceed " + MAX_EXPORT_DAYS + " days");
        var csv = Csv.builder().row("id", "occurredOn", "type", "cashDirection", "amount", "paymentMethod", "description",
                "sourceType", "sourceId", "reversesId", "createdBy", "createdAt");
        // One query = one consistent snapshot. Paging with offsets would repeat or skip rows if an entry were recorded
        // while the file is being written; the 366-day limit keeps the single result bounded.
        for (EntryView e : finance.entries(from, to, type, Pageable.unpaged(), Actor.from(jwt)))
            csv.row(e.id(), e.occurredOn(), e.type(), e.cashDirection(), e.amount(), e.method(), e.description(),
                    e.sourceType(), e.sourceId(), e.reversesId(), e.createdBy(), e.createdAt());
        return Csv.download("ledger-entries-" + from + "-to-" + to + ".csv", csv.build());
    }
}
