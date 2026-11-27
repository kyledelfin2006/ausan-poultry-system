package kyle.poultry.stock;

import static kyle.poultry.support.TestSupport.clockAt;
import static kyle.poultry.support.TestSupport.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.concurrent.atomic.AtomicLong;
import kyle.poultry.catalog.api.CatalogApi;
import kyle.poultry.catalog.api.CatalogApi.ProductView;
import kyle.poultry.finance.FinanceApi;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.StockApi.StockCommand;
import kyle.poultry.stock.StockApi.StockLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pure unit tests of {@link StockLedger}'s own decisions: which requests it refuses, in what order it touches the
 * balances, what it sends to finance, and how it reverses a stock-out.
 * <p>
 * The repositories and the other modules are mocks, because here they only supply answers (a balance update that
 * succeeds or is refused) that define the scenario. What a mock cannot prove is left to {@code StockLedgerTests} and
 * {@code WeightedAverageCostTests}, which run the real SQL: the "never below zero" statement and the average cost.
 */
@ExtendWith(MockitoExtension.class)
class StockLedgerUnitTests {

    /** The business date every test works on. */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    /** The owner, who may enter every manual type. */
    private static final Actor OWNER = new Actor(1, Role.OWNER);

    /** An assistant, who may not enter opening stock or adjustments. */
    private static final Actor ASSISTANT = new Actor(2, Role.ASSISTANT);

    @Mock StockMovementRepository movements;
    @Mock StockBalanceRepository balances;
    @Mock CatalogApi catalog;
    @Mock FinanceApi finance;
    /** The read model behind the summary and reorder levels; recording never touches it. */
    @Mock StockOverviewService overview;

    /** Hands out ids 100, 101, ... to saved movements, like the database identity column would. */
    private final AtomicLong nextId = new AtomicLong(100);

    /** The ledger under test, with "now" fixed at 10:15 on {@link #DAY} in Manila. */
    private StockLedger ledger;

    /** Builds the ledger with a fixed clock before every test. */
    @BeforeEach
    void setUp() {
        ledger = new StockLedger(movements, balances, catalog, finance, clockAt("2026-11-20T02:15:00Z"), overview);
    }

    /** Makes {@code movements.save} behave like the database: the entity comes back with an id. */
    private void saveAssignsIds() {
        when(movements.save(any(StockMovement.class)))
                .thenAnswer(call -> withId(call.<StockMovement>getArgument(0), nextId.getAndIncrement()));
    }

    /** Makes every balance update succeed, as it does when enough stock is on hand. */
    private void balanceUpdatesSucceed() {
        when(balances.applyDelta(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
    }

    /**
     * Builds a manual command for product 5 at branch 2.
     *
     * @param type   the movement type
     * @param delta  the signed quantity
     * @param cost   the unit cost, or null
     * @param on     the business date
     * @param method the payment method, or null
     * @return the command
     */
    private static StockCommand cmd(MovementType type, long delta, String cost, LocalDate on, PaymentMethod method) {
        return new StockCommand(5, 2, type, delta, cost == null ? null : new BigDecimal(cost),
                type.needsReason() ? "counted" : null, on, method);
    }

    /**
     * The delivery types, and a missing type, can never be entered by a person: they belong to the delivery flow.
     *
     * @param type a type that is not manual
     */
    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"DELIVERY_OUT", "DELIVERY_REVERSAL"})
    @NullSource
    void typesOutsideTheManualSetAreRefusedBeforeAnythingIsTouched(MovementType type) {
        var command = new StockCommand(5, 2, type, 1, null, null, DAY, null);

        assertThatThrownBy(() -> ledger.record(command, OWNER)).isInstanceOf(RuleViolation.class);

        verifyNoInteractions(catalog, balances, movements, finance);
    }

    /**
     * Opening stock and adjustments create quantities without a physical event, so an assistant is refused before
     * any stock is touched.
     *
     * @param type an owner-only type
     */
    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"OPENING", "ADJUSTMENT"})
    void anAssistantCannotEnterOwnerOnlyTypes(MovementType type) {
        assertThatThrownBy(() -> ledger.record(cmd(type, 3, null, DAY, null), ASSISTANT))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(catalog, balances, movements, finance);
    }

    /** An assistant may still record usage: the owner-only rule is about the type, not about the role in general. */
    @Test
    void anAssistantMayRecordUsage() {
        saveAssignsIds();
        balanceUpdatesSucceed();

        var saved = ledger.record(cmd(MovementType.USAGE, -2, null, DAY, null), ASSISTANT);

        assertThat(saved.type()).isEqualTo(MovementType.USAGE);
        assertThat(saved.createdBy()).isEqualTo(2);
    }

    /** Tomorrow is refused; today is allowed. */
    @Test
    void theDateMayBeTodayButNotTomorrow() {
        saveAssignsIds();
        balanceUpdatesSucceed();

        assertThatThrownBy(() -> ledger.record(cmd(MovementType.OPENING, 3, null, DAY.plusDays(1), null), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("future");
        verifyNoInteractions(balances);

        assertThat(ledger.record(cmd(MovementType.OPENING, 3, null, DAY, null), OWNER).occurredOn()).isEqualTo(DAY);
    }

    /** Assistant stock movements use the same inclusive seven-day limit as finance entries. */
    @Test
    void assistantStockMovementsCannotBeBackdatedBeyondSevenDays() {
        saveAssignsIds();
        balanceUpdatesSucceed();

        assertThat(ledger.record(cmd(MovementType.USAGE, -1, null, DAY.minusDays(7), null), ASSISTANT).occurredOn())
                .isEqualTo(DAY.minusDays(7));
        assertThatThrownBy(() -> ledger.record(cmd(MovementType.USAGE, -1, null, DAY.minusDays(8), null), ASSISTANT))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("7 days");
    }

    /**
     * "Today" is the Manila date, not the UTC date. At 01:00 on 20 November in Manila it is still 19 November in UTC,
     * and a movement dated the 20th must be accepted: a ledger that used the server's zone would wrongly refuse it.
     */
    @Test
    void todayIsTheBusinessDateNotTheUtcDate() {
        var earlyMorningInManila = clockAt("2026-11-19T17:00:00Z");
        var early = new StockLedger(movements, balances, catalog, finance, earlyMorningInManila, overview);
        saveAssignsIds();
        balanceUpdatesSucceed();

        var saved = early.record(cmd(MovementType.OPENING, 3, null, LocalDate.of(2026, 11, 20), null), OWNER);

        assertThat(saved.occurredOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    /** A purchase without a payment method is refused up front, so stock never changes without a payment record. */
    @Test
    void aPurchaseWithoutAPaymentMethodTouchesNothing() {
        assertThatThrownBy(() -> ledger.record(cmd(MovementType.PURCHASE, 5, "10", DAY, null), OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("payment method");

        verifyNoInteractions(catalog, balances, movements, finance);
    }

    /**
     * A purchase raises the balance first and then records the cash, for the cost rounded to centavos: 7 units at
     * 12.3456 is 86.4192, which must be sent to finance as 86.42 (not truncated to 86.41).
     */
    @Test
    void aPurchaseUpdatesTheBalanceThenRecordsTheRoundedCashOut() {
        saveAssignsIds();
        balanceUpdatesSucceed();
        when(catalog.requireActiveProduct(5)).thenReturn(new ProductView(5, "Chicken", "head", null, true));

        var saved = ledger.record(cmd(MovementType.PURCHASE, 7, "12.3456", DAY, PaymentMethod.GCASH), OWNER);

        assertThat(saved.id()).isEqualTo(100);
        assertThat(saved.unitCost()).isEqualTo(new BigDecimal("12.3456"));
        var order = inOrder(balances, finance);
        order.verify(balances).applyDelta(5, 2, 7, new BigDecimal("12.3456"));
        order.verify(finance).recordStockPurchase(new BigDecimal("86.42"), PaymentMethod.GCASH, DAY,
                "Purchase of 7 Chicken", new SourceRef("STOCK_MOVEMENT", 100), OWNER);
    }

    /** A donation records no cash, so finance is never called for it. */
    @Test
    void aDonationRecordsNoCash() {
        saveAssignsIds();
        balanceUpdatesSucceed();

        ledger.record(cmd(MovementType.DONATION, 10, null, DAY, null), OWNER);

        verifyNoInteractions(finance);
    }

    /** When the balance statement is refused (not enough stock), no ledger row is written and nothing is recorded. */
    @Test
    void aRefusedBalanceUpdateWritesNoMovementAndRaisesInsufficientStock() {
        when(balances.applyDelta(anyLong(), anyLong(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> ledger.record(cmd(MovementType.USAGE, -50, null, DAY, null), OWNER))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("product 5").hasMessageContaining("branch 2");

        verify(movements, never()).save(any());
        verifyNoInteractions(finance);
    }

    /**
     * A multi-line stock-out takes its lines in ascending product id whatever order they arrive in, so two concurrent
     * deliveries lock the same rows in the same order and cannot deadlock. Each line is stamped with the average cost
     * at that moment, and the returned order is the processing order.
     */
    @Test
    void deductProcessesLinesInProductOrderAndStampsTheAverageCost() {
        saveAssignsIds();
        balanceUpdatesSucceed();
        when(balances.averageCost(3, 1)).thenReturn(new BigDecimal("10.5000"));
        when(balances.averageCost(9, 1)).thenReturn(new BigDecimal("4.2500"));

        var result = ledger.deduct(List.of(new StockLine(9, 2), new StockLine(3, 5)), 1,
                new SourceRef("DELIVERY", 7), DAY, OWNER);

        // Out-of-order input, in-order processing: product 3 before product 9.
        var order = inOrder(balances);
        order.verify(balances).applyDelta(eq(3L), eq(1L), eq(-5L), isNull());
        order.verify(balances).applyDelta(eq(9L), eq(1L), eq(-2L), isNull());
        assertThat(result).extracting(StockApi.MovementView::productId).containsExactly(3L, 9L);
        assertThat(result).extracting(StockApi.MovementView::quantityDelta).containsExactly(-5L, -2L);
        // Cost of goods per line: 5 x 10.5000 = 52.50 and 2 x 4.2500 = 8.50.
        assertThat(result).extracting(StockApi.MovementView::totalCost)
                .containsExactly(new BigDecimal("52.50"), new BigDecimal("8.50"));
        assertThat(result).allSatisfy(m -> {
            assertThat(m.type()).isEqualTo(MovementType.DELIVERY_OUT);
            assertThat(m.sourceType()).isEqualTo("DELIVERY");
            assertThat(m.sourceId()).isEqualTo(7);
        });
    }

    /**
     * A zero or negative quantity would turn a stock-out into nothing or an addition, so it is refused before the
     * balance is touched.
     *
     * @param quantity a quantity that is not positive
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1, -100})
    void deductRefusesQuantitiesThatAreNotPositive(long quantity) {
        assertThatThrownBy(() -> ledger.deduct(List.of(new StockLine(3, quantity)), 1,
                new SourceRef("DELIVERY", 7), DAY, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("positive");

        verifyNoInteractions(balances, movements);
    }

    /**
     * Reversing a delivery restores only the stock-outs that were not yet reversed, in product order, each at the cost
     * it left with; the original rows are not modified.
     */
    @Test
    void reverseAllForRestoresOnlyWhatIsNotYetReversedAtTheOriginalCost() {
        var source = new SourceRef("DELIVERY", 7);
        var nine = out(11, 9, -2, "10.5000");
        var three = out(12, 3, -5, "4.2500");
        var alreadyReversed = out(13, 4, -1, "1.0000");
        when(movements.findBySourceTypeAndSourceIdAndType("DELIVERY", 7, MovementType.DELIVERY_OUT))
                .thenReturn(List.of(nine, three, alreadyReversed));
        when(movements.existsByReversesId(anyLong())).thenAnswer(call -> call.<Long>getArgument(0) == 13L);
        saveAssignsIds();
        balanceUpdatesSucceed();

        var reversals = ledger.reverseAllFor(source, "Delivery cancelled", DAY, OWNER);

        assertThat(reversals).extracting(StockApi.MovementView::productId).containsExactly(3L, 9L);
        assertThat(reversals).extracting(StockApi.MovementView::quantityDelta).containsExactly(5L, 2L);
        assertThat(reversals).extracting(StockApi.MovementView::reversesId).containsExactly(12L, 11L);
        assertThat(reversals).extracting(StockApi.MovementView::type).containsOnly(MovementType.DELIVERY_REVERSAL);
        // The goods go back at the cost they left with, so the average cost can return to where it was.
        verify(balances).applyDelta(3, 1, 5, new BigDecimal("4.2500"));
        verify(balances).applyDelta(9, 1, 2, new BigDecimal("10.5000"));
        verify(balances, never()).applyDelta(eq(4L), anyLong(), anyLong(), any());
        // Append-only: the originals keep their original quantities.
        assertThat(nine.quantityDelta()).isEqualTo(-2);
        assertThat(three.quantityDelta()).isEqualTo(-5);
    }

    /** When every stock-out already has a reversal, a second request does nothing at all. */
    @Test
    void reverseAllForIsANoOpWhenEverythingIsAlreadyReversed() {
        when(movements.findBySourceTypeAndSourceIdAndType("DELIVERY", 7, MovementType.DELIVERY_OUT))
                .thenReturn(List.of(out(11, 9, -2, "10.5000")));
        when(movements.existsByReversesId(11)).thenReturn(true);

        var reversals = ledger.reverseAllFor(new SourceRef("DELIVERY", 7), "again", DAY, OWNER);

        assertThat(reversals).isEmpty();
        verifyNoInteractions(balances);
        verify(movements, never()).save(any());
    }

    /** A product the branch never held has a quantity of zero rather than an error. */
    @Test
    void balanceOfAnUnknownRowIsZero() {
        when(balances.findById(new StockBalanceId(5, 2))).thenReturn(Optional.empty());

        assertThat(ledger.balance(5, 2)).isZero();
    }

    /** A product the branch never held has an average cost of zero at the usual four decimals, not null. */
    @Test
    void averageCostOfAnUnknownRowIsZeroAtFourDecimals() {
        when(balances.averageCost(5, 2)).thenReturn(null);

        assertThat(ledger.averageCost(5, 2)).isEqualTo(new BigDecimal("0.0000"));
    }

    /**
     * Builds a saved stock-out of product {@code product} at branch 1 for delivery 7.
     *
     * @param id       the id the database would have assigned
     * @param product  the product id
     * @param quantity the signed quantity (negative)
     * @param cost     the unit cost it left with
     * @return the saved-looking movement
     */
    private static StockMovement out(long id, long product, long quantity, String cost) {
        var command = new StockCommand(product, 1, MovementType.DELIVERY_OUT, quantity, new BigDecimal(cost), null, DAY, null);
        return withId(StockMovement.create(command, new SourceRef("DELIVERY", 7), OWNER, Instant.parse("2026-11-19T00:00:00Z")), id);
    }
}
