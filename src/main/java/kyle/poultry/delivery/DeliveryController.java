package kyle.poultry.delivery;

import kyle.poultry.identity.Capability;
import kyle.poultry.identity.RequiresCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kyle.poultry.delivery.DeliveryService.CreateRequest;
import kyle.poultry.delivery.DeliveryService.CreateResult;
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.delivery.DeliveryService.StatusCounts;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP endpoints under {@code /api/deliveries}. Any logged-in user may create, list and finish deliveries; cancel and
 * refund are enforced as owner-only in {@link DeliveryService}. Package-private; reached only over HTTP.
 */
@RestController
@RequestMapping("/api/deliveries")
class DeliveryController {
    private final DeliveryService deliveries;
    private final DeliveryReceiptService receipts;

    /**
     * Creates the controller.
     *
     * @param deliveries the delivery service
     * @param receipts   builds the owner's cost-and-profit receipt
     */
    DeliveryController(DeliveryService deliveries, DeliveryReceiptService receipts) {
        this.deliveries = deliveries; this.receipts = receipts;
    }

    /**
     * One package line in the request body; unitPrice is optional: it defaults to the package price and may be
     * overridden for this delivery only.
     *
     * @param packageId the package sold
     * @param quantity  how many, positive
     * @param unitPrice optional price override (at least 0.01, up to 2 decimals)
     */
    record LineBody(@NotNull Long packageId, @Positive long quantity,
                    @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) {}

    /**
     * Body of {@code POST /api/deliveries}.
     *
     * @param customerName  who the goods are for
     * @param driverName    who delivers them
     * @param fromBranchId  branch whose stock is used
     * @param destination   where the goods go
     * @param startedAt     optional start time; defaults to now
     * @param paymentMethod how the customer paid
     * @param lines         at least one package line
     */
    record CreateBody(@NotBlank @Size(max = 120) String customerName,
                      @NotBlank @Size(max = 120) String driverName,
                      @NotNull Long fromBranchId,
                      @NotBlank @Size(max = 255) String destination,
                      Instant startedAt,
                      @NotNull PaymentMethod paymentMethod,
                      @NotEmpty List<@Valid LineBody> lines) {}

    /**
     * Body of {@code POST /api/deliveries/{id}/done}.
     *
     * @param completedAt when the delivery arrived
     */
    record DoneBody(@NotNull Instant completedAt) {}

    /**
     * Body of {@code POST /api/deliveries/{id}/cancel}.
     *
     * @param reason why the delivery is cancelled
     */
    record CancelBody(@NotBlank @Size(max = 255) String reason) {}

    /**
     * Body of {@code POST /api/deliveries/{id}/return}.
     *
     * @param reason why the delivered goods came back
     */
    record ReturnBody(@NotBlank @Size(max = 255) String reason) {}

    /**
     * Body of {@code POST /api/deliveries/{id}/refund}.
     *
     * @param amount      money handed back, at least 0.01, up to 2 decimals
     * @param method      how it was handed back
     * @param description optional ledger note
     */
    record RefundBody(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                      @NotNull PaymentMethod method,
                      @Size(max = 255) String description) {}

    /**
     * Creates a delivery. Optional {@code Idempotency-Key} (1-100 chars): a repeat with the same key by the same user
     * returns the original delivery with 200 instead of creating another; the same key with a different body is 422.
     *
     * @param b   the request body
     * @param jwt the authenticated user
     * @param key the optional idempotency key header
     * @return 201 with the new delivery, or 200 with the original one for a repeated key
     * @throws ResponseStatusException (400) when the key is empty or longer than 100 characters
     */
    @PostMapping
    ResponseEntity<DeliveryView> create(@Valid @RequestBody CreateBody b, @AuthenticationPrincipal Jwt jwt,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && (key.isEmpty() || key.length() > 100))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-100 characters");
        var request = new CreateRequest(b.customerName(), b.driverName(), b.fromBranchId(), b.destination(),
                b.startedAt(), b.paymentMethod(),
                b.lines().stream().map(l -> new LineRequest(l.packageId(), l.quantity(), l.unitPrice())).toList());
        var actor = Actor.from(jwt);
        CreateResult result;
        try {
            result = deliveries.create(request, actor, key);
        } catch (DataIntegrityViolationException e) {
            if (key == null) throw e;
            // Lost a race on the key row: the winner has committed, so a second call returns its delivery.
            result = deliveries.create(request, actor, key);
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.delivery());
    }

    /**
     * Lists deliveries, newest first, with optional filters that are combined with AND.
     *
     * @param status   optional status filter
     * @param branchId optional: only deliveries whose stock came from this branch
     * @param customer optional: text the customer name contains, ignoring case
     * @param from     optional first business date (ISO yyyy-MM-dd) of the start time, inclusive
     * @param to       optional last business date (ISO yyyy-MM-dd) of the start time, inclusive
     * @param page     paging parameters (the sort parameter is ignored)
     * @return one page of deliveries
     * @throws kyle.poultry.shared.RuleViolation if from is after to (HTTP 422)
     */
    @GetMapping
    Page<DeliveryView> list(@RequestParam(required = false) DeliveryStatus status,
                            @RequestParam(required = false) Long branchId,
                            @RequestParam(required = false) @Size(max = 120) String customer,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            Pageable page) {
        return deliveries.list(new DeliveryFilter(status, branchId, customer, from, to), page);
    }

    /**
     * Counts deliveries per status for the same filters as the list (the status filter itself is not used).
     *
     * @param branchId optional branch filter
     * @param customer optional customer-name filter
     * @param from     optional first business date, inclusive
     * @param to       optional last business date, inclusive
     * @return the active, done, cancelled and returned counts and their total
     * @throws kyle.poultry.shared.RuleViolation if from is after to (HTTP 422)
     */
    @GetMapping("/summary")
    StatusCounts summary(@RequestParam(required = false) Long branchId,
                         @RequestParam(required = false) @Size(max = 120) String customer,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return deliveries.counts(new DeliveryFilter(null, branchId, customer, from, to));
    }

    /**
     * The owner's view of one delivery: what was sold, what it cost and what it earned, per package line and per
     * product. Owner only (403 for an assistant).
     *
     * @param id the delivery id
     * @return the receipt (404 when the delivery does not exist)
     */
    @GetMapping("/{id}/receipt")
    @RequiresCapability(Capability.VIEW_COST_AND_PROFIT)
    DeliveryReceipt receipt(@PathVariable long id) { return receipts.receipt(id); }

    /**
     * Reads one delivery.
     *
     * @param id the delivery id
     * @return the delivery (404 when it does not exist)
     */
    @GetMapping("/{id}")
    DeliveryView get(@PathVariable long id) { return deliveries.get(id); }

    /**
     * Marks a delivery as arrived.
     *
     * @param id  the delivery id
     * @param b   the completion time
     * @param jwt the authenticated user
     * @return the updated delivery (409 when it is no longer active)
     */
    @PostMapping("/{id}/done")
    DeliveryView done(@PathVariable long id, @Valid @RequestBody DoneBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.markDone(id, b.completedAt(), Actor.from(jwt));
    }

    /**
     * Cancels a delivery (owner only, enforced in the service: 403 otherwise).
     *
     * @param id  the delivery id
     * @param b   the reason
     * @param jwt the authenticated user
     * @return the cancelled delivery
     */
    @PostMapping("/{id}/cancel")
    DeliveryView cancel(@PathVariable long id, @Valid @RequestBody CancelBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.cancel(id, b.reason(), Actor.from(jwt));
    }

    /**
     * Records that a DONE delivery came back (owner only, enforced in the service: 403 otherwise). Stock goes back and
     * the sale and cost are reversed; hand money back afterwards with the refund endpoint.
     *
     * @param id  the delivery id
     * @param b   the reason
     * @param jwt the authenticated user
     * @return the returned delivery
     */
    @PostMapping("/{id}/return")
    DeliveryView returnDelivery(@PathVariable long id, @Valid @RequestBody ReturnBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.returnDelivery(id, b.reason(), Actor.from(jwt));
    }

    /**
     * Records a refund for a cancelled or returned delivery (owner only: 403 otherwise).
     *
     * @param id  the delivery id
     * @param b   the refund details
     * @param jwt the authenticated user
     * @return the delivery with the remaining retained payment
     */
    @PostMapping("/{id}/refund")
    DeliveryView refund(@PathVariable long id, @Valid @RequestBody RefundBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.refund(id, b.amount(), b.method(), b.description(), Actor.from(jwt));
    }
}
