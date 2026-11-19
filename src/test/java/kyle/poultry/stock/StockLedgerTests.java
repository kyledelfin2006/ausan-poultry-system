package kyle.poultry.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import kyle.poultry.stock.StockApi.StockCommand;
import kyle.poultry.stock.StockApi.StockLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Integration tests of the stock ledger through {@link StockApi}, against a real PostgreSQL database.
 * <p>
 * They prove the core stock promises: the balance always equals the sum of movements, stock never goes
 * negative (even with concurrent callers), multi-line stock-outs are all-or-nothing, reversals happen once,
 * and the sign, role and date rules hold. Each test creates its own product and branch so tests never
 * see each other's data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockLedgerTests {

    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;

    long ownerId;
    long productId;
    long product2Id;
    long branchId;
    Actor owner;
    LocalDate today = LocalDate.now();

    /**
     * Fresh product + branch per test, so tests never see each other's stock.
     * Names carry a random tag because product and branch names are unique in the database.
     */
    @BeforeEach
    void freshData() {
        // The owner account is created at startup from the bootstrap properties; reuse it as the acting user.
        ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        String tag = UUID.randomUUID().toString();
        productId = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "p1-" + tag);
        product2Id = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "p2-" + tag);
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
    }

    /**
     * Builds a valid command for the given product and type at the test branch, filling in whatever the type
     * requires: a unit cost of 20 and CASH for purchases, and a reason where one is mandatory.
     *
     * @param product the product id
     * @param type    the movement type
     * @param delta   the signed quantity
     * @return a command dated today
     */
    StockCommand cmd(long product, MovementType type, long delta) {
        boolean purchase = type == MovementType.PURCHASE;
        return new StockCommand(product, branchId, type, delta, purchase ? BigDecimal.valueOf(20) : null,
                type.needsReason() ? "test" : null, today, purchase ? PaymentMethod.CASH : null);
    }

    /**
     * Business rule: the balance is exactly the sum of all recorded movements.
     * (The ledger is the source of truth; the balance is a fast read model of it.)
     */
    @Test
    void balanceFollowsMovementsAndHistoryExplainsIt() {
        // Arrange + act: record opening stock, a purchase and a usage.
        stock.record(cmd(productId, MovementType.OPENING, 50), owner);
        stock.record(cmd(productId, MovementType.PURCHASE, 100), owner);
        stock.record(cmd(productId, MovementType.USAGE, -20), owner);

        // Assert: 50 + 100 - 20 = 130, both in the balance and in the raw ledger sum.
        assertThat(stock.balance(productId, branchId)).isEqualTo(130);
        assertThat(jdbc.queryForObject("select sum(quantity_delta) from stock_movement where product_id=? and branch_id=?",
                Long.class, productId, branchId)).isEqualTo(130);
    }

    /**
     * Business rule: a stock-out larger than the balance is refused and leaves no trace
     * (no balance change and no ledger row).
     */
    @Test
    void cannotDeductMoreThanAvailable_andNothingIsWritten() {
        // Arrange: 10 units on hand.
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);

        // Act + assert: asking for 11 is refused with the shortage exception.
        assertThatThrownBy(() -> stock.deduct(List.of(new StockLine(productId, 11)), branchId, new SourceRef("DELIVERY", 1), today, owner))
                .isInstanceOf(InsufficientStockException.class);

        // Assert: balance untouched and only the opening movement exists.
        assertThat(stock.balance(productId, branchId)).isEqualTo(10);
        assertThat(count("stock_movement")).isEqualTo(1);   // only the opening
    }

    /**
     * Business rule: a delivery with several lines is all-or-nothing. If the second line is short, the
     * first line's stock-out is rolled back too.
     */
    @Test
    void multiLineDeductIsAllOrNothing() {
        // Arrange: plenty of product 1, but only 1 unit of product 2.
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);
        stock.record(cmd(product2Id, MovementType.OPENING, 1), owner);

        // Act + assert: the 2-unit request for product 2 fails the whole call.
        assertThatThrownBy(() -> stock.deduct(
                List.of(new StockLine(productId, 5), new StockLine(product2Id, 2)), branchId, new SourceRef("DELIVERY", 2), today, owner))
                .isInstanceOf(InsufficientStockException.class);

        // Assert: neither product changed, so the 5 units of product 1 were not kept.
        assertThat(stock.balance(productId, branchId)).isEqualTo(10);
        assertThat(stock.balance(product2Id, branchId)).isEqualTo(1);
    }

    /**
     * Business rule: two deliveries racing for the same last stock cannot both succeed; the balance never
     * goes negative. Proves the atomic conditional update works under real concurrency.
     *
     * @throws Exception if a worker thread fails unexpectedly
     */
    @Test
    void twoConcurrentDeliveriesCannotBothTakeTheLastStock() throws Exception {
        // Arrange: 10 units on hand; two deliveries of 6 cannot both fit.
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);
        // The latch makes both threads start together to maximise the chance of a real race.
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> deliver = () -> {
                start.await();
                try {
                    // Unique source id per call so the two deliveries are distinct records.
                    stock.deduct(List.of(new StockLine(productId, 6)), branchId, new SourceRef("DELIVERY", System.nanoTime()), today, owner);
                    return true;
                } catch (InsufficientStockException e) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(deliver);
            Future<Boolean> b = pool.submit(deliver);
            // Act: release both threads at once.
            start.countDown();
            // Assert: exactly one delivery succeeded.
            assertThat(Stream.of(a.get(), b.get()).filter(ok -> ok).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        // Assert: 10 - 6 = 4 left; the loser took nothing.
        assertThat(stock.balance(productId, branchId)).isEqualTo(4);
    }

    /**
     * Business rule: cancelling a delivery restores its stock exactly once, even if the cancellation is
     * requested twice, and the original ledger rows are kept (append-only).
     */
    @Test
    void reversalRestoresStockOnceEvenIfRequestedTwice() {
        // Arrange: 10 on hand, then a delivery takes 7.
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);
        var source = new SourceRef("DELIVERY", 99);
        stock.deduct(List.of(new StockLine(productId, 7)), branchId, source, today, owner);
        assertThat(stock.balance(productId, branchId)).isEqualTo(3);

        // Act + assert: the first reversal creates one movement, the second finds nothing to reverse.
        assertThat(stock.reverseAllFor(source, "cancelled", today, owner)).hasSize(1);
        assertThat(stock.reverseAllFor(source, "cancelled", today, owner)).isEmpty();

        // Assert: stock is back to 10 (not 17) and nothing was deleted.
        assertThat(stock.balance(productId, branchId)).isEqualTo(10);
        assertThat(count("stock_movement")).isEqualTo(3);   // opening, out, reversal: original rows kept
    }

    /**
     * Business rule: each movement type may only move stock in its allowed direction, and a reason is
     * mandatory for usage. Both the Java rules and the database check constraint refuse bad input.
     */
    @Test
    void movementSignRulesAreEnforcedInJavaAndInTheDatabase() {
        // Java rules: a negative purchase, a positive usage and a manual delivery stock-out are all refused.
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.PURCHASE, -5), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.USAGE, 5), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.DELIVERY_OUT, -5), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(new StockCommand(productId, branchId, MovementType.USAGE, -1, null, null, today, null), owner))
                .isInstanceOf(RuleViolation.class);   // usage needs a reason

        // Bypass Java entirely: the database refuses a negative purchase too.
        assertThatThrownBy(() -> jdbc.update("""
                insert into stock_movement (product_id, branch_id, type, quantity_delta, occurred_on, created_by, created_at)
                values (?, ?, 'PURCHASE', -5, current_date, ?, now())""", productId, branchId, ownerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Business rule: opening stock and adjustments change quantities without a physical event, so only the
     * owner may enter them; an assistant may still record routine entries such as purchases.
     */
    @Test
    void assistantCannotEnterOpeningStockOrAdjustments() {
        // Arrange: same user id, but with the assistant role (only the role matters for this rule).
        var assistant = new Actor(ownerId, Role.ASSISTANT);
        // Act + assert: owner-only types are refused with an access-denied error.
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.OPENING, 5), assistant)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.ADJUSTMENT, 5), assistant)).isInstanceOf(AccessDeniedException.class);
        stock.record(cmd(productId, MovementType.PURCHASE, 5), assistant);   // routine entries are allowed
    }

    /** Business rule: a movement cannot be dated tomorrow or later; business dates may not be in the future. */
    @Test
    void futureDatesAreRejected() {
        // Arrange: a purchase otherwise valid, but dated tomorrow.
        var future = new StockCommand(productId, branchId, MovementType.PURCHASE, 5, BigDecimal.valueOf(20), null, today.plusDays(1), PaymentMethod.CASH);
        // Act + assert: refused with a rule violation.
        assertThatThrownBy(() -> stock.record(future, owner)).isInstanceOf(RuleViolation.class);
    }

    /**
     * Counts ledger rows of this test's own products at this test's branch, straight from the database.
     *
     * @param table the table to count (a fixed name from the tests, never user input)
     * @return the number of rows for the test's products and branch
     */
    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where product_id in (?, ?) and branch_id = ?",
                Long.class, productId, product2Id, branchId);
    }
}
