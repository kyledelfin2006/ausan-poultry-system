package kyle.poultry.stock.internal.web;

import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.model.MovementType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.stock.api.StockApi.BalanceView;
import kyle.poultry.stock.api.StockApi.MovementView;
import kyle.poultry.stock.api.StockApi.ProductStockView;
import kyle.poultry.stock.api.StockApi.ReorderLevelView;
import kyle.poultry.stock.api.StockApi.StockCommand;
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
     * @throws kyle.poultry.shared.error.RuleViolation if the ledger rejects the movement (HTTP 422)
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
    List<BalanceView> balances(@RequestParam(required = false) Long branchId,
                               @AuthenticationPrincipal Jwt jwt) {
        boolean owner = Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT);
        return stock.balances(branchId).stream().map(b -> owner ? b
                : new BalanceView(b.productId(), b.productName(), b.unit(), b.branchId(), b.quantity(), null)).toList();
    }

    /**
     * Pages through the movement history of one product at one branch, newest first.
     *
     * @param productId the product (required)
     * @param branchId  the branch (required)
     * @param page      paging parameters from the query string
     * @return one page of movements
     */
    @GetMapping("/movements")
    Page<MovementView> history(@RequestParam long productId, @RequestParam long branchId, Pageable page,
                               @AuthenticationPrincipal Jwt jwt) {
        Actor actor = Actor.from(jwt);
        return stock.history(productId, branchId, page).map(movement -> {
            boolean canSeeCost = actor.can(Capability.VIEW_COST_AND_PROFIT) || (movement.type() == MovementType.PURCHASE
                    && movement.createdBy() == actor.userId());
            return canSeeCost ? movement : withoutCost(movement);
        });
    }

    private static MovementView withoutCost(MovementView movement) {
        return new MovementView(movement.id(), movement.productId(), movement.branchId(), movement.type(),
                movement.quantityDelta(), null, movement.reason(), movement.occurredOn(), movement.sourceType(),
                movement.sourceId(), movement.reversesId(), movement.createdBy(), movement.createdAt());
    }

    /**
     * Body of {@code PUT /api/stock/reorder-levels/{productId}}.
     *
     * @param level units at or below which a branch counts as low on the product; 0 or more
     */
    record ReorderLevelBody(@NotNull @PositiveOrZero Long level) {}

    /**
     * Stock per product across all branches, with low-stock flags (any role).
     *
     * @param lowOnly true to list only products that are low; default false
     * @return products ordered by name, each with its per-branch quantities
     */
    @GetMapping("/summary")
    List<ProductStockView> summary(@RequestParam(defaultValue = "false") boolean lowOnly,
                                   @AuthenticationPrincipal Jwt jwt) {
        boolean owner = Actor.from(jwt).can(Capability.VIEW_COST_AND_PROFIT);
        return stock.summary(lowOnly).stream().map(p -> owner ? p : new ProductStockView(
                p.productId(), p.productName(), p.unit(), p.totalQuantity(), p.reorderLevel(), p.low(),
                p.branches().stream().map(b -> new StockApi.BranchStockView(b.branchId(), b.quantity(), null, b.low())).toList()))
                .toList();
    }

    /**
     * Lists the configured reorder levels (any role).
     *
     * @return levels ordered by product name
     */
    @GetMapping("/reorder-levels")
    List<ReorderLevelView> reorderLevels() { return stock.reorderLevels(); }

    /**
     * Sets or replaces a product's reorder level (owner only: 403 otherwise).
     *
     * @param productId the product
     * @param b         the level
     * @param jwt       the caller's token
     * @return the stored level
     */
    @PutMapping("/reorder-levels/{productId}")
    ReorderLevelView setReorderLevel(@PathVariable long productId, @Valid @RequestBody ReorderLevelBody b,
                                     @AuthenticationPrincipal Jwt jwt) {
        return stock.setReorderLevel(productId, b.level(), Actor.from(jwt));
    }

    /**
     * Removes a product's reorder level (owner only: 403 otherwise; 404 when none is set).
     *
     * @param productId the product
     * @param jwt       the caller's token
     */
    @DeleteMapping("/reorder-levels/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearReorderLevel(@PathVariable long productId, @AuthenticationPrincipal Jwt jwt) {
        stock.clearReorderLevel(productId, Actor.from(jwt));
    }
}
