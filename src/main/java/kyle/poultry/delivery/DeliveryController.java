package kyle.poultry.delivery;

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
import java.util.List;
import kyle.poultry.delivery.DeliveryService.CreateRequest;
import kyle.poultry.delivery.DeliveryService.CreateResult;
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Creates the controller.
     *
     * @param deliveries the delivery service
     */
    DeliveryController(DeliveryService deliveries) { this.deliveries = deliveries; }

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
     * Lists deliveries, newest first.
     *
     * @param status optional status filter
     * @param page   paging parameters
     * @return one page of deliveries
     */
    @GetMapping
    Page<DeliveryView> list(@RequestParam(required = false) DeliveryStatus status, Pageable page) {
        return deliveries.list(status, page);
    }

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
     * Records a refund for a cancelled delivery (owner only: 403 otherwise).
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
