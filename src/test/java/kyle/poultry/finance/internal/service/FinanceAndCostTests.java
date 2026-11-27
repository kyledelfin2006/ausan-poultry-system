package kyle.poultry.finance.internal.service;

import kyle.poultry.finance.api.FinanceApi;
import kyle.poultry.finance.api.model.PaymentMethod;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.MovementType;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.StockCommand;
import kyle.poultry.stock.StockApi.StockLine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Integration tests of the finance ledger and weighted average costing against a real PostgreSQL (Testcontainers):
 * purchases and average cost, sale/cost/payment linkage, cancellation reversals, refunds, amount validation and
 * the database's own consistency checks. Each test uses fresh product and branch rows because the database is shared.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FinanceAndCostTests {

    @Autowired StockApi stock;
    @Autowired FinanceApi finance;
    @Autowired JdbcTemplate jdbc;

    long ownerId, productId, branchId;
    Actor owner, assistant;
    @Autowired java.time.Clock clock;
    LocalDate today;

    /** Looks up the bootstrap owner and creates a fresh product and branch, so tests never share stock rows. */
    @BeforeEach
    void freshData() {
        // "Today" comes from the application clock (Manila), as in the app; the machine zone (UTC on CI) can be a day behind.
        today = LocalDate.now(clock);
        // The owner exists in every test database; assistant is the same user acting with a lower role.
        ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        assistant = new Actor(ownerId, Role.ASSISTANT);
        // A random tag keeps names unique across tests that share the database.
        String tag = UUID.randomUUID().toString();
        productId = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id",
                Long.class, "nipple-" + tag);
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
    }

    /**
     * Buys stock for the test product through the stock module (which also records the cash out).
     *
     * @param qty      units bought
     * @param unitCost price per unit, as text
     * @return the stock movement created
     */
    MovementView purchase(long qty, String unitCost) {
        return stock.record(new StockCommand(productId, branchId, MovementType.PURCHASE, qty, new BigDecimal(unitCost),
                null, today, PaymentMethod.CASH), owner);
    }

    /**
     * Sums the ledger amounts of one entry type for one source, straight from the database.
     *
     * @param type   entry type name, e.g. SALE
     * @param source the source record
     * @return the total, zero if none
     */
    BigDecimal cash(String type, SourceRef source) {
        return jdbc.queryForObject("""
                select coalesce(sum(amount),0) from financial_entry
                 where type=? and source_type=? and source_id=?""",
                BigDecimal.class, type, source.type(), source.id());
    }

    /** Returns a delivery reference with a random id, so each test's ledger rows are isolated from the others. */
    SourceRef newDelivery() {
        return new SourceRef("DELIVERY", ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE));
    }

    /** Each purchase writes a STOCK_PURCHASE cash-out and moves the weighted average cost. */
    @Test
    void buyingStockRecordsTheCashOutAndBuildsTheAverageCost() {
        var first = purchase(100, "20.00");
        assertThat(cash("STOCK_PURCHASE", new SourceRef("STOCK_MOVEMENT", first.id()))).isEqualByComparingTo("2000.00");
        assertThat(stock.averageCost(productId, branchId)).isEqualByComparingTo("20");

        var second = purchase(100, "22.00");
        assertThat(cash("STOCK_PURCHASE", new SourceRef("STOCK_MOVEMENT", second.id())))
                .isEqualByComparingTo("2200.00");
        assertThat(stock.averageCost(productId, branchId)).isEqualByComparingTo("21");
        assertThat(stock.balance(productId, branchId)).isEqualTo(200);
    }

    /** Opening stock costs nothing, writes no cash entry and lowers the average of later purchases. */
    @Test
    void openingStockAndDonationsCostNothingAndPullTheAverageDown() {
        // Opening stock has no unit cost and no payment method: nothing was paid for it.
        var opening = stock.record(
                new StockCommand(productId, branchId, MovementType.OPENING, 50, null, null, today, null), owner);
        assertThat(opening.unitCost()).isEqualByComparingTo("0");
        assertThat(cash("STOCK_PURCHASE", new SourceRef("STOCK_MOVEMENT", opening.id()))).isEqualByComparingTo("0");

        purchase(50, "20.00");
        assertThat(stock.averageCost(productId, branchId)).isEqualByComparingTo("10");   // (50*0 + 50*20) / 100
    }

    /** Delivery stock-out is frozen at the average cost of that moment; reversal restores stock at that cost. */
    @Test
    void deliveryStockOutIsStampedWithAverageCostAndReversalDoesNotMoveTheAverage() {
        purchase(100, "20.00");
        purchase(100, "22.00");   // average 21
        var source = newDelivery();

        var out = stock.deduct(List.of(new StockLine(productId, 30)), branchId, source, today, owner);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).unitCost()).isEqualByComparingTo("21");
        assertThat(out.get(0).totalCost()).isEqualByComparingTo("630.00");      // 30 x 21: the cost of goods
        assertThat(stock.balance(productId, branchId)).isEqualTo(170);

        // A later purchase changes the average, but the delivered cost stays frozen on its movement.
        purchase(30, "30.00");
        var reversal = stock.reverseAllFor(source, "cancelled", today, owner);
        assertThat(reversal.get(0).unitCost()).isEqualByComparingTo("21");
        assertThat(stock.balance(productId, branchId)).isEqualTo(230);
    }

    /** A purchase without a payment method or unit cost is refused and leaves no stock behind. */
    @Test
    void purchaseNeedsPaymentMethodAndCost_andWritesNothingOtherwise() {
        assertThatThrownBy(() -> stock.record(new StockCommand(productId, branchId, MovementType.PURCHASE, 10,
                new BigDecimal("20"), null, today, null), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(new StockCommand(productId, branchId, MovementType.PURCHASE, 10,
                null, null, today, PaymentMethod.CASH), owner)).isInstanceOf(RuleViolation.class);
        assertThat(stock.balance(productId, branchId)).isZero();
    }

    /** Cancelling reverses sale and cost exactly once (idempotent), keeps originals and keeps the payment held. */
    @Test
    void saleCostAndPaymentAreLinkedAndCancellationReversesSaleAndCostOnceButKeepsThePayment() {
        var d = newDelivery();
        finance.recordSale(new BigDecimal("1000.00"), today, d, owner);
        finance.recordCostOfGoods(new BigDecimal("630.00"), today, d, owner);
        finance.recordPayment(new BigDecimal("1000.00"), PaymentMethod.GCASH, today, d, owner);

        finance.reverseSaleAndCost(d, today, owner);
        finance.reverseSaleAndCost(d, today, owner);   // idempotent

        assertThat(cash("SALE_REVERSAL", d)).isEqualByComparingTo("1000.00");
        assertThat(cash("COGS_REVERSAL", d)).isEqualByComparingTo("630.00");
        assertThat(cash("SALE", d)).isEqualByComparingTo("1000.00");            // originals retained
        assertThat(finance.retainedPayment(d)).isEqualByComparingTo("1000.00");  // money still held until refunded
    }

    /** A zero cost of goods (donated stock) writes no ledger row. */
    @Test
    void zeroCostOfGoodsRecordsNothing() {
        var d = newDelivery();
        finance.recordCostOfGoods(BigDecimal.ZERO, today, d, owner);
        assertThat(cash("COST_OF_GOODS", d)).isEqualByComparingTo("0");
    }

    /** Only the owner can refund, and never more than the payment still held. */
    @Test
    void refundIsOwnerOnlyAndCappedByTheRetainedPayment() {
        var d = newDelivery();
        finance.recordPayment(new BigDecimal("1000.00"), PaymentMethod.CASH, today, d, owner);

        // An assistant is refused regardless of the amount.
        assertThatThrownBy(() -> finance.recordRefund(new BigDecimal("100"), PaymentMethod.CASH, today, "x", d,
                assistant))
                .isInstanceOf(AccessDeniedException.class);

        finance.recordRefund(new BigDecimal("400.00"), PaymentMethod.CASH, today, "partial", d, owner);
        assertThat(finance.retainedPayment(d)).isEqualByComparingTo("600.00");

        // Only 600.00 is still held, so 700.00 must be refused.
        assertThatThrownBy(() -> finance.recordRefund(new BigDecimal("700.00"), PaymentMethod.CASH, today,
                "too much", d, owner))
                .isInstanceOf(RuleViolation.class);

        finance.recordRefund(new BigDecimal("600.00"), PaymentMethod.CASH, today, "rest", d, owner);
        assertThat(finance.retainedPayment(d)).isEqualByComparingTo("0");
    }

    /** Amounts must be positive with at most 2 decimals, and an expense needs a description. */
    @Test
    void amountsMustBePositivePesoValuesAndExpensesNeedADescription() {
        var d = newDelivery();
        // Zero, negative and sub-centavo amounts are all rejected.
        assertThatThrownBy(() -> finance.recordSale(BigDecimal.ZERO, today, d, owner))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> finance.recordSale(new BigDecimal("-5"), today, d, owner))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> finance.recordSale(new BigDecimal("10.005"), today, d, owner))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> finance.recordExpense(new BigDecimal("50"), PaymentMethod.CASH, today, " ", assistant))
                .isInstanceOf(RuleViolation.class);
        assertThat(finance.recordExpense(new BigDecimal("50"), PaymentMethod.CASH, today, "Fuel", assistant).amount())
                .isEqualByComparingTo("50.00");
    }

    /** The table constraints reject a wrong cash direction and a cash entry without a method. */
    @Test
    void theDatabaseRejectsEntriesWhoseDirectionContradictsTheirType() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into financial_entry (type, cash_direction, amount, method, occurred_on, created_by, created_at)
                values ('PAYMENT_IN', 'OUT', 10, 'CASH', current_date, ?, now())""", ownerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into financial_entry (type, cash_direction, amount, occurred_on, created_by, created_at)
                values ('EXPENSE', 'OUT', 10, current_date, ?, now())""", ownerId))   // cash entry without a method
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
