package kyle.poultry.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.stock.StockApi.BalanceView;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.StockCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stock")
class StockController {
    private final StockApi stock;

    StockController(StockApi stock) { this.stock = stock; }

    /**
     * quantityDelta is signed: positive adds stock, negative removes it. The type decides which sign is legal.
     * unitCost is required for PURCHASE (OPENING and DONATION default to 0); paymentMethod is required for PURCHASE.
     */
    record RecordMovement(@NotNull Long productId, @NotNull Long branchId, @NotNull MovementType type,
                          long quantityDelta,
                          @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitCost,
                          PaymentMethod paymentMethod,
                          @Size(max = 255) String reason,
                          @NotNull LocalDate occurredOn) {}

    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    MovementView record(@Valid @RequestBody RecordMovement r, @AuthenticationPrincipal Jwt jwt) {
        return stock.record(new StockCommand(r.productId(), r.branchId(), r.type(), r.quantityDelta(),
                r.unitCost(), r.reason(), r.occurredOn(), r.paymentMethod()), Actor.from(jwt));
    }

    @GetMapping("/balances")
    List<BalanceView> balances(@RequestParam(required = false) Long branchId) { return stock.balances(branchId); }

    @GetMapping("/movements")
    Page<MovementView> history(@RequestParam long productId, @RequestParam long branchId, Pageable page) {
        return stock.history(productId, branchId, page);
    }
}
