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
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deliveries")
class DeliveryController {
    private final DeliveryService deliveries;

    DeliveryController(DeliveryService deliveries) { this.deliveries = deliveries; }

    /** unitPrice is optional: it defaults to the package price and may be overridden for this delivery only. */
    record LineBody(@NotNull Long packageId, @Positive long quantity,
                    @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) {}

    record CreateBody(@NotBlank @Size(max = 120) String customerName,
                      @NotBlank @Size(max = 120) String driverName,
                      @NotNull Long fromBranchId,
                      @NotBlank @Size(max = 255) String destination,
                      Instant startedAt,
                      @NotNull PaymentMethod paymentMethod,
                      @NotEmpty List<@Valid LineBody> lines) {}

    record DoneBody(@NotNull Instant completedAt) {}

    record CancelBody(@NotBlank @Size(max = 255) String reason) {}

    record RefundBody(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                      @NotNull PaymentMethod method,
                      @Size(max = 255) String description) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeliveryView create(@Valid @RequestBody CreateBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.create(new CreateRequest(b.customerName(), b.driverName(), b.fromBranchId(), b.destination(),
                b.startedAt(), b.paymentMethod(),
                b.lines().stream().map(l -> new LineRequest(l.packageId(), l.quantity(), l.unitPrice())).toList()), Actor.from(jwt));
    }

    @GetMapping
    Page<DeliveryView> list(@RequestParam(required = false) DeliveryStatus status, Pageable page) {
        return deliveries.list(status, page);
    }

    @GetMapping("/{id}")
    DeliveryView get(@PathVariable long id) { return deliveries.get(id); }

    @PostMapping("/{id}/done")
    DeliveryView done(@PathVariable long id, @Valid @RequestBody DoneBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.markDone(id, b.completedAt(), Actor.from(jwt));
    }

    @PostMapping("/{id}/cancel")
    DeliveryView cancel(@PathVariable long id, @Valid @RequestBody CancelBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.cancel(id, b.reason(), Actor.from(jwt));
    }

    @PostMapping("/{id}/refund")
    DeliveryView refund(@PathVariable long id, @Valid @RequestBody RefundBody b, @AuthenticationPrincipal Jwt jwt) {
        return deliveries.refund(id, b.amount(), b.method(), b.description(), Actor.from(jwt));
    }
}
