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

/**
 * REST endpoints for stock under {@code /api/stock}: record a manual movement, list balances, read history.
 * <p>
 * The controller only converts HTTP into {@link StockApi} calls and holds no business rules; those, and the
 * owner-only checks for opening stock and adjustments, are enforced in the stock ledger. The signed-in user
 * is taken from the JWT of the request.
 */
@RestController
@RequestMapping("/api/stock")
class StockController {
    private final StockApi stock;

    /**
     * Creates the controller.
     *
     * @param stock the stock module API
     */
    StockController(StockApi stock) { this.stock = stock; }

    /**
     * Request body for a manual stock movement.
     * <p>
     * quantityDelta is signed: positive adds stock, negative removes it. The type decides which sign is legal.
     * unitCost is required for PURCHASE (OPENING and DONATION default to 0); paymentMethod is required for PURCHASE.
     *
     * @param productId     required product id
     * @param branchId      required branch id
     * @param type          required movement type
     * @param quantityDelta signed units; zero is rejected by the ledger
     * @param unitCost      optional, not below 0.00, at most 10 integer and 2 fraction digits
     * @param paymentMethod how a purchase was paid; required for PURCHASE
     * @param reason        optional free text of at most 255 characters; required for USAGE and ADJUSTMENT
     * @param occurredOn    required business date
     */
    record RecordMovement(@NotNull Long productId, @NotNull Long branchId, @NotNull MovementType type,
                          long quantityDelta,
                          @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitCost,
                          PaymentMethod paymentMethod,
                          @Size(max = 255) String reason,
                          @NotNull LocalDate occurredOn) {}

    /**
     * Records one manual movement (opening, purchase, donation, usage or adjustment).
     *
     * @param r   the validated request body
     * @param jwt the caller's token, used to identify the acting user
     * @return the saved movement, with HTTP 201
     * @throws kyle.poultry.shared.RuleViolation if the ledger rejects the movement (HTTP 422)
     * @throws InsufficientStockException if the movement would make the balance negative (HTTP 409)
     */
    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    MovementView record(@Valid @RequestBody RecordMovement r, @AuthenticationPrincipal Jwt jwt) {
        // Build the command and resolve the actor from the token; the ledger does the real validation.
        return stock.record(new StockCommand(r.productId(), r.branchId(), r.type(), r.quantityDelta(),
                r.unitCost(), r.reason(), r.occurredOn(), r.paymentMethod()), Actor.from(jwt));
    }

    /**
     * Lists current stock balances with average cost.
     *
     * @param branchId optional branch filter; all branches when omitted
     * @return the balances, ordered by branch then product
     */
    @GetMapping("/balances")
    List<BalanceView> balances(@RequestParam(required = false) Long branchId) { return stock.balances(branchId); }

    /**
     * Pages through the movement history of one product at one branch, newest first.
     *
     * @param productId the product (required)
     * @param branchId  the branch (required)
     * @param page      paging parameters from the query string
     * @return one page of movements
     */
    @GetMapping("/movements")
    Page<MovementView> history(@RequestParam long productId, @RequestParam long branchId, Pageable page) {
        return stock.history(productId, branchId, page);
    }
}
