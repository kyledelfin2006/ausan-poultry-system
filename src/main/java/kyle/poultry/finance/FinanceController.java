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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance")
class FinanceController {
    private final FinanceApi finance;

    FinanceController(FinanceApi finance) { this.finance = finance; }

    record RecordExpense(@NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                         @NotNull PaymentMethod method,
                         @NotNull LocalDate occurredOn,
                         @NotBlank @Size(max = 255) String description) {}

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    EntryView expense(@Valid @RequestBody RecordExpense r, @AuthenticationPrincipal Jwt jwt) {
        return finance.recordExpense(r.amount(), r.method(), r.occurredOn(), r.description(), Actor.from(jwt));
    }

    @GetMapping("/entries")
    Page<EntryView> entries(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) EntryType type,
                            Pageable page) {
        return finance.entries(from, to, type, page);
    }
}
