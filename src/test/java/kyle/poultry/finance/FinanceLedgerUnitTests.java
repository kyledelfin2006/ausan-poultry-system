package kyle.poultry.finance;

import static kyle.poultry.support.TestSupport.clockAt;
import static kyle.poultry.support.TestSupport.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pure unit tests of {@link FinanceLedger}: the money rules that need no database. They cover which amounts are
 * accepted, what each entry type requires, the owner-only refund and its cap, how a cancellation reverses a sale and
 * its cost exactly once, and that the report aggregates always list every type and method.
 * <p>
 * The repository is a mock that returns the sums and rows the scenario needs; the real SQL aggregates are proved by
 * {@code FinanceAndCostTests} and {@code ReportTests} against PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class FinanceLedgerUnitTests {

    /** Today in the business zone (the clock below is 10:15 on this date in Manila). */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    /** The owner, who may refund. */
    private static final Actor OWNER = new Actor(1, Role.OWNER);

    /** An assistant, who may not. */
    private static final Actor ASSISTANT = new Actor(2, Role.ASSISTANT);

    /** The delivery a few of the tests record money for. */
    private static final SourceRef DELIVERY_7 = new SourceRef("DELIVERY", 7);

    @Mock FinancialEntryRepository entries;

    /** Hands out ids 500, 501, ... to saved entries. */
    private final AtomicLong nextId = new AtomicLong(500);

    /** The ledger under test. */
    private FinanceLedger ledger;

    /** Builds the ledger with a fixed clock before every test. */
    @BeforeEach
    void setUp() {
        ledger = new FinanceLedger(entries, clockAt("2026-11-20T02:15:00Z"));
    }

    /** Makes {@code entries.save} behave like the database: the entity comes back with an id. */
    private void saveAssignsIds() {
        when(entries.save(any(FinancialEntry.class)))
                .thenAnswer(call -> withId(call.<FinancialEntry>getArgument(0), nextId.getAndIncrement()));
    }

    /**
     * Amounts must be positive pesos with at most two decimals, because centavos are the smallest unit and a third
     * decimal would be silently rounded money.
     *
     * @param amount an amount that must be refused
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01", "-100", "0.001", "10.005", "1.2345"})
    void amountsThatAreNotPositivePesoValuesAreRefused(String amount) {
        assertThatThrownBy(() -> ledger.recordExpense(new BigDecimal(amount), PaymentMethod.CASH, DAY, "fuel", OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Amount");

        verifyNoInteractions(entries);
    }

    /** A missing amount is refused like any other invalid amount. */
    @ParameterizedTest
    @NullSource
    void aMissingAmountIsRefused(BigDecimal amount) {
        assertThatThrownBy(() -> ledger.recordExpense(amount, PaymentMethod.CASH, DAY, "fuel", OWNER))
                .isInstanceOf(RuleViolation.class);
    }

    /**
     * Valid amounts are stored at exactly two decimals, including a value written with trailing zeros ("100.500" has
     * three decimals but only two significant ones).
     *
     * @param given    the amount as sent
     * @param expected the amount as stored
     */
    @ParameterizedTest
    @CsvSource({"100.5,100.50", "100.500,100.50", "0.01,0.01", "70000,70000.00", "1234567.89,1234567.89"})
    void validAmountsAreStoredAtTwoDecimals(String given, String expected) {
        saveAssignsIds();

        var entry = ledger.recordExpense(new BigDecimal(given), PaymentMethod.CASH, DAY, "fuel", OWNER);

        assertThat(entry.amount()).isEqualTo(new BigDecimal(expected));
        assertThat(entry.type()).isEqualTo(EntryType.EXPENSE);
        assertThat(entry.cashDirection()).isEqualTo(EntryType.CashDirection.OUT);
    }

    /** An expense has no source record, so the description is its only explanation and cannot be blank. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void anExpenseNeedsADescription(String description) {
        assertThatThrownBy(() -> ledger.recordExpense(new BigDecimal("10"), PaymentMethod.CASH, DAY, description, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("description");
    }

    /** Cash cannot be booked ahead of time: tomorrow is refused, today is fine. */
    @Test
    void anExpenseCannotBeDatedInTheFuture() {
        saveAssignsIds();

        assertThatThrownBy(() -> ledger.recordExpense(new BigDecimal("10"), PaymentMethod.CASH, DAY.plusDays(1), "fuel", OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("future");
        assertThat(ledger.recordExpense(new BigDecimal("10"), PaymentMethod.CASH, DAY, "fuel", OWNER).occurredOn()).isEqualTo(DAY);
    }

    /** Assistant expense entries accept the inclusive seven-day boundary and reject older dates. */
    @Test
    void assistantExpenseBackdatingIsLimitedToSevenDays() {
        saveAssignsIds();

        assertThat(ledger.recordExpense(new BigDecimal("10"), PaymentMethod.CASH, DAY.minusDays(7), "fuel", ASSISTANT)
                .occurredOn()).isEqualTo(DAY.minusDays(7));
        assertThatThrownBy(() -> ledger.recordExpense(new BigDecimal("10"), PaymentMethod.CASH,
                DAY.minusDays(8), "fuel", ASSISTANT))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("7 days");
    }

    /** Cash entries must say how the money moved. */
    @Test
    void anExpenseNeedsAPaymentMethod() {
        assertThatThrownBy(() -> ledger.recordExpense(new BigDecimal("10"), null, DAY, "fuel", OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("payment method");

        verify(entries, never()).save(any());
    }

    /** Sales, costs and payments always belong to a source record (the delivery), so a missing one is refused. */
    @Test
    void salesCostsAndPaymentsNeedASource() {
        assertThatThrownBy(() -> ledger.recordSale(new BigDecimal("100"), DAY, null, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("source");
        assertThatThrownBy(() -> ledger.recordCostOfGoods(new BigDecimal("100"), DAY, null, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("source");
        assertThatThrownBy(() -> ledger.recordPayment(new BigDecimal("100"), PaymentMethod.CASH, DAY, null, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("source");
    }

    /** A sale moves no cash: the entry has no direction and no payment method. */
    @Test
    void aSaleMovesNoCash() {
        saveAssignsIds();

        var sale = ledger.recordSale(new BigDecimal("70000"), DAY, DELIVERY_7, ASSISTANT);

        assertThat(sale.type()).isEqualTo(EntryType.SALE);
        assertThat(sale.cashDirection()).isEqualTo(EntryType.CashDirection.NONE);
        assertThat(sale.method()).isNull();
        assertThat(sale.sourceType()).isEqualTo("DELIVERY");
        assertThat(sale.sourceId()).isEqualTo(7);
    }

    /** A payment is cash in and must say how it was received. */
    @Test
    void aPaymentIsCashInAndNeedsAMethod() {
        saveAssignsIds();

        var payment = ledger.recordPayment(new BigDecimal("70000"), PaymentMethod.GCASH, DAY, DELIVERY_7, ASSISTANT);

        assertThat(payment.cashDirection()).isEqualTo(EntryType.CashDirection.IN);
        assertThat(payment.method()).isEqualTo(PaymentMethod.GCASH);
        assertThatThrownBy(() -> ledger.recordPayment(new BigDecimal("1"), null, DAY, DELIVERY_7, ASSISTANT))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("payment method");
    }

    /** Donated or opening stock costs nothing: a zero cost of goods is skipped rather than refused. */
    @Test
    void aZeroCostOfGoodsRecordsNothing() {
        ledger.recordCostOfGoods(new BigDecimal("0.00"), DAY, DELIVERY_7, OWNER);
        ledger.recordCostOfGoods(BigDecimal.ZERO, DAY, DELIVERY_7, OWNER);

        verifyNoInteractions(entries);
    }

    /** A negative or missing cost of goods is an error, not a skip. */
    @Test
    void aNegativeOrMissingCostOfGoodsIsRefused() {
        assertThatThrownBy(() -> ledger.recordCostOfGoods(new BigDecimal("-1"), DAY, DELIVERY_7, OWNER))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> ledger.recordCostOfGoods(null, DAY, DELIVERY_7, OWNER))
                .isInstanceOf(RuleViolation.class);
    }

    /** A positive cost of goods is stored as a COST_OF_GOODS entry for the source. */
    @Test
    void aPositiveCostOfGoodsIsStored() {
        saveAssignsIds();

        ledger.recordCostOfGoods(new BigDecimal("680"), DAY, DELIVERY_7, OWNER);

        var saved = ArgumentCaptor.forClass(FinancialEntry.class);
        verify(entries).save(saved.capture());
        var view = saved.getValue().view();
        assertThat(view.type()).isEqualTo(EntryType.COST_OF_GOODS);
        assertThat(view.amount()).isEqualTo(new BigDecimal("680.00"));
    }

    /** Handing money back is the owner's decision: an assistant is refused before the ledger is even read. */
    @Test
    void anAssistantCannotRefund() {
        assertThatThrownBy(() -> ledger.recordRefund(new BigDecimal("10"), PaymentMethod.CASH, DAY, "x", DELIVERY_7, ASSISTANT))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(entries);
    }

    /**
     * A refund can never exceed what was received and not yet returned. With 70,000.00 received and 20,000.00
     * already returned, 50,000.00 is still held: that exact amount is allowed and one centavo more is refused.
     */
    @Test
    void aRefundIsCappedByThePaymentStillHeld() {
        when(entries.sum("DELIVERY", 7, EntryType.PAYMENT_IN)).thenReturn(new BigDecimal("70000.00"));
        when(entries.sum("DELIVERY", 7, EntryType.REFUND)).thenReturn(new BigDecimal("20000.00"));
        saveAssignsIds();

        assertThatThrownBy(() -> ledger.recordRefund(new BigDecimal("50000.01"), PaymentMethod.CASH, DAY, "x", DELIVERY_7, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("exceeds");
        verify(entries, never()).save(any());

        var refund = ledger.recordRefund(new BigDecimal("50000.00"), PaymentMethod.CASH, DAY, "x", DELIVERY_7, OWNER);

        assertThat(refund.type()).isEqualTo(EntryType.REFUND);
        assertThat(refund.cashDirection()).isEqualTo(EntryType.CashDirection.OUT);
        assertThat(refund.amount()).isEqualTo(new BigDecimal("50000.00"));
    }

    /** A refund dated tomorrow is refused even when the amount is fine. */
    @Test
    void aRefundCannotBeDatedInTheFuture() {
        assertThatThrownBy(() -> ledger.recordRefund(new BigDecimal("10"), PaymentMethod.CASH, DAY.plusDays(1), "x", DELIVERY_7, OWNER))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("future");
    }

    /** The money still held is everything received minus everything already handed back. */
    @Test
    void retainedPaymentIsPaymentsMinusRefunds() {
        when(entries.sum("DELIVERY", 7, EntryType.PAYMENT_IN)).thenReturn(new BigDecimal("70000.00"));
        when(entries.sum("DELIVERY", 7, EntryType.REFUND)).thenReturn(new BigDecimal("12345.67"));

        assertThat(ledger.retainedPayment(DELIVERY_7)).isEqualTo(new BigDecimal("57654.33"));
    }

    /**
     * Cancelling reverses the sale and its cost, but a row that already has a reversal is skipped, so a second call
     * cannot reverse twice. Here the sale was reversed before; only the cost is reversed now, for the same amount.
     */
    @Test
    void reverseSaleAndCostSkipsWhatIsAlreadyReversed() {
        var now = Instant.parse("2026-11-19T00:00:00Z");
        var sale = withId(FinancialEntry.of(EntryType.SALE, new BigDecimal("70000.00"), null, DAY, null, DELIVERY_7, OWNER, now), 1);
        var cost = withId(FinancialEntry.of(EntryType.COST_OF_GOODS, new BigDecimal("680.00"), null, DAY, null, DELIVERY_7, OWNER, now), 2);
        when(entries.findBySourceTypeAndSourceIdAndTypeIn("DELIVERY", 7, List.of(EntryType.SALE, EntryType.COST_OF_GOODS)))
                .thenReturn(List.of(sale, cost));
        when(entries.existsByReversesId(anyLong())).thenAnswer(call -> call.<Long>getArgument(0) == 1L);
        saveAssignsIds();

        ledger.reverseSaleAndCost(DELIVERY_7, DAY, OWNER);

        var saved = ArgumentCaptor.forClass(FinancialEntry.class);
        verify(entries).save(saved.capture());
        var reversal = saved.getValue().view();
        assertThat(reversal.type()).isEqualTo(EntryType.COGS_REVERSAL);
        assertThat(reversal.amount()).isEqualTo(new BigDecimal("680.00"));
        assertThat(reversal.reversesId()).isEqualTo(2);
        assertThat(reversal.cashDirection()).isEqualTo(EntryType.CashDirection.NONE);
    }

    /** Only revenue and cost can be reversed; money rows are corrected with a refund instead. */
    @Test
    void onlySalesAndCostsCanBeReversed() {
        var payment = withId(FinancialEntry.of(EntryType.PAYMENT_IN, new BigDecimal("10.00"), PaymentMethod.CASH, DAY, null,
                DELIVERY_7, OWNER, Instant.parse("2026-11-19T00:00:00Z")), 3);

        assertThatThrownBy(() -> payment.reversal(DAY, OWNER, Instant.parse("2026-11-20T00:00:00Z")))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("cannot be reversed");
    }

    /** Totals always list every entry type, with zero for the types that have no entries. */
    @Test
    void totalsByTypeListsEveryTypeWithZeroForTheMissingOnes() {
        var from = DAY.minusDays(6);
        when(entries.totalsByType(from, DAY)).thenReturn(List.<Object[]>of(
                new Object[] {EntryType.SALE, new BigDecimal("1500.00")},
                new Object[] {EntryType.EXPENSE, new BigDecimal("20.50")}));

        var totals = ledger.totalsByType(from, DAY);

        assertThat(totals).containsOnlyKeys(EntryType.values());
        assertThat(totals.get(EntryType.SALE)).isEqualTo(new BigDecimal("1500.00"));
        assertThat(totals.get(EntryType.EXPENSE)).isEqualTo(new BigDecimal("20.50"));
        assertThat(totals.get(EntryType.REFUND)).isEqualTo(new BigDecimal("0.00"));
    }

    /** The per-method cash breakdown lists every method, with zero for the unused ones. */
    @Test
    void cashByMethodListsEveryMethodWithZeroForTheUnusedOnes() {
        when(entries.cashByMethod(DAY, DAY, EntryType.CashDirection.IN)).thenReturn(List.<Object[]>of(
                new Object[] {PaymentMethod.GCASH, new BigDecimal("-30.00")}));

        var cash = ledger.cashByMethod(DAY, DAY);

        assertThat(cash).containsOnlyKeys(PaymentMethod.values());
        assertThat(cash.get(PaymentMethod.GCASH)).isEqualTo(new BigDecimal("-30.00"));
        assertThat(cash.get(PaymentMethod.CASH)).isEqualTo(new BigDecimal("0.00"));
    }

    /**
     * Every period query shares one rule: both dates present and in order. A single-day period (from equals to) is
     * valid.
     */
    @Test
    void periodsMustHaveBothDatesInOrder() {
        assertThatThrownBy(() -> ledger.totalsByType(DAY, DAY.minusDays(1))).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> ledger.totalsByType(null, DAY)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> ledger.cashByMethod(DAY, null)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> ledger.entries(DAY, DAY.minusDays(1), null,
                org.springframework.data.domain.Pageable.unpaged(), OWNER))
                .isInstanceOf(RuleViolation.class);

        assertThat(ledger.totalsByType(DAY, DAY)).isNotEmpty();
    }

    /** The retained-on-cancelled figure needs an as-of date. */
    @Test
    void retainedOnCancelledNeedsAnAsOfDate() {
        assertThatThrownBy(() -> ledger.retainedOnCancelledSources(null)).isInstanceOf(RuleViolation.class);
    }
}
