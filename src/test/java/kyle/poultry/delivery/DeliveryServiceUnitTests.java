package kyle.poultry.delivery;

import static kyle.poultry.support.TestSupport.clockAt;
import static kyle.poultry.support.TestSupport.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kyle.poultry.catalog.api.CatalogApi;
import kyle.poultry.catalog.api.model.PackageItemView;
import kyle.poultry.catalog.api.model.PackageView;
import kyle.poultry.delivery.DeliveryService.CreateRequest;
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.shared.error.ConflictException;
import kyle.poultry.shared.error.NotFoundException;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.MovementType;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.StockLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pure unit tests of {@link DeliveryService}'s own logic: how a request is validated, how packages become stock
 * lines, how the sale, cost and payment are worked out, which calendar day the ledgers are dated, and who may cancel
 * or refund.
 * <p>
 * Stock, finance and catalog are mocks that only supply answers (a package's contents, the cost the stock-out
 * reports). What needs the real database - the idempotency lock, the row locks, the rollback of a failed delivery - is
 * proved by {@code DeliveryTests}, {@code DeliveryIdempotencyTests} and {@code DeliveryConcurrencyTests}.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryServiceUnitTests {

    /** 10:15 on 20 November 2026 in Manila. */
    private static final String MORNING = "2026-11-20T02:15:00Z";

    /** The owner, who may cancel and refund. */
    private static final Actor OWNER = new Actor(1, Role.OWNER);

    /** An assistant, who may create and complete deliveries only. */
    private static final Actor ASSISTANT = new Actor(2, Role.ASSISTANT);

    @Mock DeliveryRepository deliveries;
    @Mock CatalogApi catalog;
    @Mock StockApi stock;
    @Mock FinanceApi finance;
    @Mock JdbcTemplate jdbc;

    /** The service under test, built on the morning clock. */
    private DeliveryService service;

    /** Builds the service with a fixed clock before every test. */
    @BeforeEach
    void setUp() {
        service = serviceAt(clockAt(MORNING));
    }

    /**
     * Builds the service on a given clock.
     *
     * @param clock the clock the service reads "now" and the time zone from
     * @return a service wired to this test's mocks
     */
    private DeliveryService serviceAt(Clock clock) {
        return new DeliveryService(deliveries, catalog, stock, finance, clock, jdbc);
    }

    /** Package 10 costs 1,000.00 and holds 3 of product 5 and 1 of product 6 (listed out of product order on purpose). */
    private void packageA() {
        when(catalog.requireActivePackage(10)).thenReturn(new PackageView(10, "PKG-A", "Package A", new BigDecimal("1000.00"), true,
                List.of(new PackageItemView(6, "Y", 1), new PackageItemView(5, "X", 3))));
    }

    /** Package 11 costs 500.50 and holds 4 of product 5. */
    private void packageB() {
        when(catalog.requireActivePackage(11)).thenReturn(new PackageView(11, "PKG-B", "Package B", new BigDecimal("500.50"), true,
                List.of(new PackageItemView(5, "X", 4))));
    }

    /** Makes the repository hand out delivery number 5 and save with id 77, like the sequence and identity column would. */
    private void repositoryNumbersAndSaves() {
        when(deliveries.nextCodeNumber()).thenReturn(5L);
        when(deliveries.save(any(Delivery.class))).thenAnswer(call -> withId(call.<Delivery>getArgument(0), 77));
    }

    /**
     * Builds the stock-out result for a delivery: one movement per product with the cost it left at.
     *
     * @param product  product id
     * @param quantity units removed (positive)
     * @param cost     cost per unit at that moment
     * @return the movement view
     */
    private static MovementView out(long product, long quantity, String cost) {
        return new MovementView(1000 + product, product, 1, MovementType.DELIVERY_OUT, -quantity, new BigDecimal(cost), null,
                LocalDate.of(2026, 11, 20), "DELIVERY", 77L, null, 1, Instant.parse(MORNING));
    }

    /**
     * Builds a request for the given lines, from branch 1, paid by GCash.
     *
     * @param lines the package lines
     * @return the request
     */
    private static CreateRequest request(LineRequest... lines) {
        return new CreateRequest("Juan", "Pedro", 1, "Tarlac", null, PaymentMethod.GCASH, List.of(lines));
    }

    /**
     * Builds a saved ACTIVE delivery with code DLV-2026-0005 and id 77.
     *
     * @return the delivery
     */
    private static Delivery savedDelivery() {
        var line = new DeliveryLine(10, "Package A", 1, new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        return withId(Delivery.open("DLV-2026-0005", "Juan", "Pedro", 1, "Tarlac", Instant.parse("2026-11-20T01:00:00Z"),
                PaymentMethod.CASH, List.of(line), 1, Instant.parse("2026-11-20T01:00:00Z")), 77);
    }

    /**
     * Two packages that both contain product 5 are merged into one stock line for it, lines are taken in ascending
     * product order, and the cost of goods is the sum of what the stock-out reports. Worked by hand: product 5 is
     * 3 x 2 + 4 x 1 = 10 units, product 6 is 1 x 2 = 2 units; costs are 10 x 3.3333 = 33.33 and 2 x 7.5 = 15.00, so
     * the cost of goods is 48.33; the total is 2 x 1,000.00 + 1 x 500.50 = 2,500.50.
     */
    @Test
    void createMergesPackageContentsDeductsInProductOrderAndRecordsSaleCostAndPayment() {
        packageA();
        packageB();
        repositoryNumbersAndSaves();
        when(stock.deduct(any(), eq(1L), any(), any(), any())).thenReturn(List.of(out(5, 10, "3.3333"), out(6, 2, "7.5000")));

        var view = service.create(request(new LineRequest(10, 2, null), new LineRequest(11, 1, null)), ASSISTANT);

        assertThat(view.total()).isEqualTo(new BigDecimal("2500.50"));
        assertThat(view.status()).isEqualTo(DeliveryStatus.ACTIVE);
        assertThat(view.lines()).extracting(DeliveryLine::lineTotal)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("500.50"));
        var source = new SourceRef("DELIVERY", 77);
        var day = LocalDate.of(2026, 11, 20);
        var order = inOrder(stock, finance);
        order.verify(stock).deduct(List.of(new StockLine(5, 10), new StockLine(6, 2)), 1, source, day, ASSISTANT);
        order.verify(finance).recordSale(new BigDecimal("2500.50"), day, source, ASSISTANT);
        order.verify(finance).recordCostOfGoods(new BigDecimal("48.33"), day, source, ASSISTANT);
        order.verify(finance).recordPayment(new BigDecimal("2500.50"), PaymentMethod.GCASH, day, source, ASSISTANT);
        verifyNoInteractions(jdbc);
    }

    /**
     * The delivery code and the ledger date use the Manila calendar, not UTC. At 01:30 on 1 January 2027 in Manila it is
     * still 31 December 2026 in UTC; the code must carry 2027 and the money must be dated 1 January.
     */
    @Test
    void codeAndLedgerDateFollowTheManilaCalendarAtTheYearBoundary() {
        var newYear = serviceAt(clockAt("2026-12-31T17:30:00Z"));
        packageB();
        repositoryNumbersAndSaves();
        when(stock.deduct(any(), eq(1L), any(), any(), any())).thenReturn(List.of(out(5, 3, "10.0000")));

        var view = newYear.create(request(new LineRequest(11, 1, null)), OWNER);

        assertThat(view.code()).isEqualTo("DLV-2027-0005");
        verify(finance).recordSale(new BigDecimal("500.50"), LocalDate.of(2027, 1, 1), new SourceRef("DELIVERY", 77), OWNER);
    }

    /**
     * A price override applies to this delivery only and is stored at two decimals: 65,000.5 becomes 65,000.50 and
     * 10.000 (three decimals, two significant) is accepted.
     */
    @Test
    void aPriceOverrideIsUsedAndStoredAtTwoDecimals() {
        packageB();
        repositoryNumbersAndSaves();
        when(stock.deduct(any(), eq(1L), any(), any(), any())).thenReturn(List.of(out(5, 3, "10.0000")));

        var view = service.create(request(new LineRequest(11, 3, new BigDecimal("65000.5"))), OWNER);

        assertThat(view.lines().getFirst().unitPrice()).isEqualTo(new BigDecimal("65000.50"));
        assertThat(view.total()).isEqualTo(new BigDecimal("195001.50"));
        var tenPesos = service.create(request(new LineRequest(11, 1, new BigDecimal("10.000"))), OWNER);
        assertThat(tenPesos.total()).isEqualTo(new BigDecimal("10.00"));
    }

    /**
     * Prices must be above zero and have at most two real decimals; refused requests touch neither stock nor finance.
     *
     * @param price an unacceptable price
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "0.001", "99.999", "10.005"})
    void unacceptablePricesAreRefusedBeforeStockOrMoneyIsTouched(String price) {
        packageA();

        assertThatThrownBy(() -> service.create(request(new LineRequest(10, 1, new BigDecimal(price))), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Price");

        verifyNoInteractions(stock, finance);
        verify(deliveries, never()).save(any());
    }

    /**
     * Customer, driver and destination are mandatory and may not be blank.
     *
     * @param blank a missing or blank text
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void customerDriverAndDestinationAreMandatory(String blank) {
        var line = new LineRequest(10, 1, null);

        assertThatThrownBy(() -> service.create(new CreateRequest(blank, "Pedro", 1, "Tarlac", null, PaymentMethod.CASH, List.of(line)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Customer name");
        assertThatThrownBy(() -> service.create(new CreateRequest("Juan", blank, 1, "Tarlac", null, PaymentMethod.CASH, List.of(line)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Driver name");
        assertThatThrownBy(() -> service.create(new CreateRequest("Juan", "Pedro", 1, blank, null, PaymentMethod.CASH, List.of(line)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Destination");

        verifyNoInteractions(stock, finance, catalog);
    }

    /** A delivery is paid in full when created, so the payment method is required; so is at least one package. */
    @Test
    void aPaymentMethodAndAtLeastOnePackageAreRequired() {
        var line = new LineRequest(10, 1, null);

        assertThatThrownBy(() -> service.create(new CreateRequest("Juan", "Pedro", 1, "Tarlac", null, null, List.of(line)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Payment method");
        assertThatThrownBy(() -> service.create(new CreateRequest("Juan", "Pedro", 1, "Tarlac", null, PaymentMethod.CASH, List.of()), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("at least one package");
        assertThatThrownBy(() -> service.create(new CreateRequest("Juan", "Pedro", 1, "Tarlac", null, PaymentMethod.CASH, null), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("at least one package");

        verifyNoInteractions(stock, finance);
    }

    /**
     * A quantity of zero or less, and the same package listed twice, are refused: the second would double-count a line.
     */
    @Test
    void quantitiesMustBePositiveAndPackagesCannotRepeat() {
        assertThatThrownBy(() -> service.create(request(new LineRequest(10, 0, null)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("quantity must be positive");
        assertThatThrownBy(() -> service.create(request(new LineRequest(10, -2, null)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("quantity must be positive");
        packageA();
        assertThatThrownBy(() -> service.create(request(new LineRequest(10, 1, null), new LineRequest(10, 2, null)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("listed twice");

        verifyNoInteractions(stock, finance);
    }

    /**
     * The trip may start now but not in the future. The clock has whole seconds and storage keeps microseconds, so the
     * boundary is exactly "now" (accepted) versus one microsecond later (refused).
     */
    @Test
    void theStartTimeMayBeNowButNotLater() {
        var now = Instant.parse(MORNING);
        packageA();
        repositoryNumbersAndSaves();
        when(stock.deduct(any(), eq(1L), any(), any(), any())).thenReturn(List.of(out(5, 3, "10.0000")));
        var line = List.of(new LineRequest(10, 1, null));

        var atNow = service.create(new CreateRequest("Juan", "Pedro", 1, "Tarlac", now, PaymentMethod.CASH, line), OWNER);
        assertThat(atNow.startedAt()).isEqualTo(now);

        assertThatThrownBy(() -> service.create(
                new CreateRequest("Juan", "Pedro", 1, "Tarlac", now.plusNanos(1_000), PaymentMethod.CASH, line), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("future");
    }

    /** Assistant deliveries may use a business date exactly seven days back but not an older one. */
    @Test
    void assistantDeliveryStartDateHasASevenDayWindow() {
        packageA();
        repositoryNumbersAndSaves();
        when(stock.deduct(any(), eq(1L), any(), any(), any())).thenReturn(List.of(out(5, 3, "10.0000")));
        var line = List.of(new LineRequest(10, 1, null));
        Instant cutoff = Instant.parse("2026-11-13T02:15:00Z");

        var accepted = service.create(new CreateRequest("Juan", "Pedro", 1, "Tarlac", cutoff,
                PaymentMethod.CASH, line), ASSISTANT);
        assertThat(accepted.startedAt()).isEqualTo(cutoff);
        assertThatThrownBy(() -> service.create(new CreateRequest("Juan", "Pedro", 1, "Tarlac",
                Instant.parse("2026-11-12T02:15:00Z"), PaymentMethod.CASH, line), ASSISTANT))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("7 days");
    }

    /** Only the owner may cancel: an assistant is refused before the delivery is even loaded. */
    @Test
    void anAssistantCannotCancel() {
        assertThatThrownBy(() -> service.cancel(77, "reason", ASSISTANT)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(deliveries, stock, finance);
    }

    /** Cancelling an unknown delivery is a not-found, with the id in the message. */
    @Test
    void cancellingAnUnknownDeliveryIsNotFound() {
        when(deliveries.lockById(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(9, "reason", OWNER))
                .isInstanceOf(NotFoundException.class).hasMessage("Delivery 9 not found");
    }

    /** Cancelling restores stock first and then reverses the sale and cost, and reports what the customer is still owed. */
    @Test
    void cancelRestoresStockThenReversesSaleAndCostAndReportsTheRetainedPayment() {
        var delivery = savedDelivery();
        var source = new SourceRef("DELIVERY", 77);
        when(deliveries.lockById(77)).thenReturn(Optional.of(delivery));
        when(finance.retainedPayment(source)).thenReturn(new BigDecimal("1000.00"));

        var view = service.cancel(77, "Customer cancelled", OWNER);

        var day = LocalDate.of(2026, 11, 20);
        var order = inOrder(stock, finance);
        order.verify(stock).reverseAllFor(source, "Delivery DLV-2026-0005 cancelled", day, OWNER);
        order.verify(finance).reverseSaleAndCost(source, day, OWNER);
        assertThat(view.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(view.cancelReason()).isEqualTo("Customer cancelled");
        assertThat(view.retainedPayment()).isEqualTo(new BigDecimal("1000.00"));
    }

    /** A cancellation without a reason is refused before any stock or money is reversed, and the delivery stays ACTIVE. */
    @Test
    void cancelWithoutAReasonReversesNothing() {
        var delivery = savedDelivery();
        when(deliveries.lockById(77)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.cancel(77, "  ", OWNER)).isInstanceOf(RuleViolation.class);

        verifyNoInteractions(stock, finance);
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.ACTIVE);
    }

    /** A finished delivery cannot be cancelled and nothing is reversed: the conflict is raised before any effect. */
    @Test
    void aDoneDeliveryCannotBeCancelledAndNothingIsReversed() {
        var delivery = savedDelivery();
        delivery.markDone(Instant.parse(MORNING), Instant.parse(MORNING));
        when(deliveries.lockById(77)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.cancel(77, "too late", OWNER)).isInstanceOf(ConflictException.class);

        verifyNoInteractions(stock, finance);
    }

    /** Marking done records the arrival only: no stock and no money move. */
    @Test
    void markDoneHasNoStockOrMoneyEffect() {
        when(deliveries.lockById(77)).thenReturn(Optional.of(savedDelivery()));

        var view = service.markDone(77, Instant.parse(MORNING), ASSISTANT);

        assertThat(view.status()).isEqualTo(DeliveryStatus.DONE);
        assertThat(view.completedAt()).isEqualTo(Instant.parse(MORNING));
        verifyNoInteractions(stock, finance);
    }

    /** Only the owner may refund. */
    @Test
    void anAssistantCannotRefund() {
        assertThatThrownBy(() -> service.refund(77, new BigDecimal("10"), PaymentMethod.CASH, null, ASSISTANT))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(deliveries, finance);
    }

    /** Only a cancelled delivery can be refunded; the message names the delivery and its actual status. */
    @Test
    void anActiveDeliveryCannotBeRefunded() {
        when(deliveries.lockById(77)).thenReturn(Optional.of(savedDelivery()));

        assertThatThrownBy(() -> service.refund(77, new BigDecimal("10"), PaymentMethod.CASH, null, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("DLV-2026-0005").hasMessageContaining("ACTIVE");

        verifyNoInteractions(finance);
    }

    /**
     * A refund of a cancelled delivery goes to finance with the given description, or a default naming the delivery
     * when the description is missing or blank.
     */
    @Test
    void aRefundUsesTheDescriptionOrADefaultNamingTheDelivery() {
        var delivery = savedDelivery();
        delivery.cancel("changed mind", Instant.parse(MORNING));
        when(deliveries.lockById(77)).thenReturn(Optional.of(delivery));
        when(finance.retainedPayment(new SourceRef("DELIVERY", 77))).thenReturn(new BigDecimal("990.00"));
        var source = new SourceRef("DELIVERY", 77);
        var day = LocalDate.of(2026, 11, 20);

        service.refund(77, new BigDecimal("10.00"), PaymentMethod.GCASH, " ", OWNER);
        service.refund(77, new BigDecimal("20.00"), PaymentMethod.CASH, "Goodwill", OWNER);

        verify(finance).recordRefund(new BigDecimal("10.00"), PaymentMethod.GCASH, day, "Refund for DLV-2026-0005", source, OWNER);
        verify(finance).recordRefund(new BigDecimal("20.00"), PaymentMethod.CASH, day, "Goodwill", source, OWNER);
    }

    /** A package whose contents multiply past the range of a long is refused instead of wrapping to a small number. */
    @Test
    void refusesALineWhoseExpandedQuantityOverflows() {
        when(catalog.requireActivePackage(12)).thenReturn(new PackageView(12, "PKG-HUGE", "Huge", new BigDecimal("1.00"), true,
                List.of(new PackageItemView(5, "X", 1L << 62))));

        assertThatThrownBy(() -> service.create(request(new LineRequest(12, 4, null)), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("too large");
        verifyNoInteractions(stock);
    }
}
