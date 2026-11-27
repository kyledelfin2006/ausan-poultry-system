package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.delivery.DeliveryService.CreateRequest;
import kyle.poultry.delivery.DeliveryService.CreateResult;
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.shared.error.ConflictException;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.stock.api.exception.InsufficientStockException;
import kyle.poultry.stock.api.model.MovementType;
import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.StockApi.StockCommand;
import kyle.poultry.support.ConcurrentRunner;
import kyle.poultry.support.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Races real threads against a real PostgreSQL database to prove the promises that only hold under concurrency: stock is
 * never oversold, a delivery is cancelled or finished but never both, a cancellation restores stock once, refunds never
 * exceed the payment, a retried request with the same key creates one delivery, and overlapping deliveries cannot
 * deadlock.
 * <p>
 * Each test builds its own branch, products and packages, so the tests do not see each other's data. Threads are
 * released together by {@link ConcurrentRunner}, waits are bounded, and every failure is classified: a test fails when
 * a task throws anything other than the failure the business rule predicts (a deadlock or a constraint error would
 * show up as an unexpected exception).
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeliveryConcurrencyTests {

    @Autowired DeliveryService deliveries;
    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private Actor owner;
    private long ownerId;
    private LocalDate today;

    /** Looks up the bootstrap owner and today's date; each test then builds its own {@link Shop}. */
    @BeforeEach
    void setUp() {
        ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        today = LocalDate.now(clock);
    }

    /**
     * One isolated branch with a chicken and a cage product and two packages:
     * package A is 24 chickens + 2 cages for 70,000.00 and package B is 1 cage + 12 chickens for 40,000.00 (its items
     * are written in the opposite product order on purpose).
     *
     * @param branchId the branch holding the stock
     * @param chicken  the chicken product
     * @param cage     the cage product
     * @param packageA the larger package
     * @param packageB the smaller package
     */
    private record Shop(long branchId, long chicken, long cage, long packageA, long packageB) {}

    /**
     * Builds a shop and buys stock into it (chickens at 10.00 each, cages at 50.00 each).
     *
     * @param chickens chickens to buy
     * @param cages    cages to buy
     * @return the new shop
     */
    private Shop shopWith(long chickens, long cages) {
        String tag = UUID.randomUUID().toString();
        long branch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        long chicken = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "chicken-" + tag);
        long cage = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "cage-" + tag);
        long a = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 70000) returning id", Long.class, "A-" + tag, "A " + tag);
        long b = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 40000) returning id", Long.class, "B-" + tag, "B " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 24), (?, ?, 2)", a, chicken, a, cage);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 1), (?, ?, 12)", b, cage, b, chicken);
        if (chickens > 0) buy(branch, chicken, chickens, "10.00");
        if (cages > 0) buy(branch, cage, cages, "50.00");
        return new Shop(branch, chicken, cage, a, b);
    }

    /**
     * Buys stock as the owner.
     *
     * @param branch   the branch
     * @param product  the product
     * @param quantity units bought
     * @param unitCost cost per unit
     */
    private void buy(long branch, long product, long quantity, String unitCost) {
        stock.record(new StockCommand(product, branch, MovementType.PURCHASE, quantity, new BigDecimal(unitCost), null, today,
                PaymentMethod.CASH), owner);
    }

    /**
     * Builds a delivery request for one package.
     *
     * @param shop     the shop
     * @param pkg      the package id
     * @param quantity packages sold
     * @param customer the customer name
     * @return the request
     */
    private static CreateRequest request(Shop shop, long pkg, long quantity, String customer) {
        return new CreateRequest(customer, "Pedro", shop.branchId(), "Davao", null, PaymentMethod.GCASH,
                List.of(new LineRequest(pkg, quantity, null)));
    }

    /**
     * Counts a delivery's stock movements of one type.
     *
     * @param deliveryId the delivery
     * @param type       the movement type
     * @return how many rows exist
     */
    private long movements(long deliveryId, MovementType type) {
        return jdbc.queryForObject("select count(*) from stock_movement where source_type = 'DELIVERY' and source_id = ? and type = ?",
                Long.class, deliveryId, type.name());
    }

    /**
     * Counts a delivery's finance entries of one type.
     *
     * @param deliveryId the delivery
     * @param type       the entry type
     * @return how many rows exist
     */
    private long entries(long deliveryId, String type) {
        return jdbc.queryForObject("select count(*) from financial_entry where source_type = 'DELIVERY' and source_id = ? and type = ?",
                Long.class, deliveryId, type);
    }

    /**
     * Counts the deliveries of a branch.
     *
     * @param shop the shop
     * @return how many deliveries were saved
     */
    private long deliveriesOf(Shop shop) {
        return jdbc.queryForObject("select count(*) from delivery where from_branch_id = ?", Long.class, shop.branchId());
    }

    /**
     * Asserts that every failed outcome is one of the expected business failures; anything else (a deadlock, a
     * constraint violation, a timeout) fails the test with that exception.
     *
     * @param outcomes the race result
     * @param allowed  the exception type the business rule predicts
     */
    private static void onlyFailuresOfType(List<? extends Outcome<?>> outcomes, Class<? extends Throwable> allowed) {
        for (Outcome<?> o : outcomes) {
            if (!o.succeeded()) assertThat(o.error()).as("unexpected failure under concurrency").isInstanceOf(allowed);
        }
    }

    /**
     * Exactly one of two simultaneous deliveries can have the last package. Repeated five times with a fresh shop each
     * round, because a race is only found by trying several times. The loser leaves no trace: no delivery row, no
     * stock movement, no money.
     */
    @Test
    void twoDeliveriesCompetingForTheLastPackageProduceExactlyOne() throws Exception {
        for (int round = 1; round <= 5; round++) {
            var shop = shopWith(24, 2);
            List<Callable<CreateResult>> tasks = List.of(
                    () -> deliveries.create(request(shop, shop.packageA(), 1, "first"), owner, null),
                    () -> deliveries.create(request(shop, shop.packageA(), 1, "second"), owner, null));

            var outcomes = ConcurrentRunner.race(tasks);

            assertThat(outcomes.stream().filter(Outcome::succeeded)).as("round %d winners", round).hasSize(1);
            onlyFailuresOfType(outcomes, InsufficientStockException.class);
            long winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value().delivery().id();
            assertThat(stock.balance(shop.chicken(), shop.branchId())).isZero();
            assertThat(stock.balance(shop.cage(), shop.branchId())).isZero();
            assertThat(deliveriesOf(shop)).isEqualTo(1);
            assertThat(movements(winner, MovementType.DELIVERY_OUT)).isEqualTo(2);
            // Sale, payment and cost of goods (24 x 10.00 + 2 x 50.00 = 340.00) exist once, for the winner only.
            assertThat(entries(winner, "SALE")).isEqualTo(1);
            assertThat(entries(winner, "PAYMENT_IN")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select amount from financial_entry where source_id = ? and source_type = 'DELIVERY' and type = 'COST_OF_GOODS'",
                    BigDecimal.class, winner)).isEqualByComparingTo("340.00");
        }
    }

    /**
     * Twelve distinct-key deliveries race for stock that fits exactly five packages: five are created, seven are
     * refused with "insufficient stock", the shelf ends at zero (never negative), and only the five winners keep an
     * idempotency key (a refused request does not consume its key).
     */
    @Test
    void twelveDeliveriesForFiveSlotsCreateFiveAndNeverOversell() throws Exception {
        var shop = shopWith(24 * 5, 2 * 5);
        String batch = UUID.randomUUID().toString();
        List<Callable<CreateResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int n = i;
            tasks.add(() -> deliveries.create(request(shop, shop.packageA(), 1, "c" + n), owner, batch + "-" + n));
        }

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(5);
        onlyFailuresOfType(outcomes, InsufficientStockException.class);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isZero();
        assertThat(stock.balance(shop.cage(), shop.branchId())).isZero();
        assertThat(deliveriesOf(shop)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from delivery_idempotency where idempotency_key like ?", Long.class, batch + "-%"))
                .isEqualTo(5);
    }

    /**
     * Deliveries that overlap on the same products but list them in opposite orders (package A and package B) are
     * taken in one fixed product order, so none deadlocks: all ten succeed and the stock is exactly what is left.
     * Five of A (24 chickens, 2 cages) and five of B (12 chickens, 1 cage) take 180 chickens and 15 cages.
     */
    @Test
    void overlappingPackagesInOppositeOrdersNeverDeadlock() throws Exception {
        var shop = shopWith(200, 20);
        List<Callable<CreateResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long pkg = i % 2 == 0 ? shop.packageA() : shop.packageB();
            int n = i;
            tasks.add(() -> deliveries.create(request(shop, pkg, 1, "c" + n), owner, null));
        }

        var outcomes = ConcurrentRunner.race(tasks);

        onlyFailuresOfType(outcomes, InsufficientStockException.class);
        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(200 - 180);
        assertThat(stock.balance(shop.cage(), shop.branchId())).isEqualTo(20 - 15);
    }

    /**
     * Six simultaneous cancellations of one delivery: exactly one succeeds and the other five are conflicts, stock
     * returns exactly once (one reversal per product), and the sale and cost are each reversed exactly once.
     */
    @Test
    void concurrentCancellationsRestoreStockAndReverseTheLedgerExactlyOnce() throws Exception {
        var shop = shopWith(24, 2);
        long id = deliveries.create(request(shop, shop.packageA(), 1, "c"), owner).id();
        List<Callable<DeliveryView>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) tasks.add(() -> deliveries.cancel(id, "customer cancelled", owner));

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        onlyFailuresOfType(outcomes, ConflictException.class);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(24);
        assertThat(stock.balance(shop.cage(), shop.branchId())).isEqualTo(2);
        assertThat(movements(id, MovementType.DELIVERY_REVERSAL)).isEqualTo(2);
        assertThat(entries(id, "SALE_REVERSAL")).isEqualTo(1);
        assertThat(entries(id, "COGS_REVERSAL")).isEqualTo(1);
        assertThat(deliveries.get(id).status()).isEqualTo(DeliveryStatus.CANCELLED);
    }

    /**
     * Finishing and cancelling the same delivery at the same time: exactly one wins, and the books agree with the
     * winner. If "done" wins nothing is reversed and the stock stays out; if "cancel" wins the stock is back and the
     * delivery is cancelled. Repeated eight times because the winner varies from run to run.
     */
    @Test
    void doneAndCancelRaceHasOneWinnerAndConsistentBooks() throws Exception {
        for (int round = 1; round <= 8; round++) {
            var shop = shopWith(24, 2);
            long id = deliveries.create(request(shop, shop.packageA(), 1, "c"), owner).id();
            var arrival = clock.instant();
            List<Callable<DeliveryView>> tasks = List.of(
                    () -> deliveries.markDone(id, arrival, owner),
                    () -> deliveries.cancel(id, "customer cancelled", owner));

            var outcomes = ConcurrentRunner.race(tasks);

            assertThat(outcomes.stream().filter(Outcome::succeeded)).as("round %d winners", round).hasSize(1);
            onlyFailuresOfType(outcomes, ConflictException.class);
            var finalStatus = deliveries.get(id).status();
            if (finalStatus == DeliveryStatus.DONE) {
                assertThat(stock.balance(shop.chicken(), shop.branchId())).isZero();
                assertThat(movements(id, MovementType.DELIVERY_REVERSAL)).isZero();
                assertThat(entries(id, "SALE_REVERSAL")).isZero();
            } else {
                assertThat(finalStatus).isEqualTo(DeliveryStatus.CANCELLED);
                assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(24);
                assertThat(movements(id, MovementType.DELIVERY_REVERSAL)).isEqualTo(2);
                assertThat(entries(id, "SALE_REVERSAL")).isEqualTo(1);
            }
        }
    }

    /**
     * Six simultaneous refunds of 20,000.00 against a cancelled delivery paid 70,000.00: only three fit, so exactly
     * three succeed, three are refused as rule violations, and 10,000.00 stays on record as still held.
     */
    @Test
    void concurrentRefundsNeverExceedThePayment() throws Exception {
        var shop = shopWith(24, 2);
        long id = deliveries.create(request(shop, shop.packageA(), 1, "c"), owner).id();
        deliveries.cancel(id, "customer cancelled", owner);
        List<Callable<DeliveryView>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) tasks.add(() -> deliveries.refund(id, new BigDecimal("20000.00"), PaymentMethod.GCASH, null, owner));

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(3);
        onlyFailuresOfType(outcomes, RuleViolation.class);
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from financial_entry where source_type = 'DELIVERY' and source_id = ? and type = 'REFUND'",
                BigDecimal.class, id)).isEqualByComparingTo("60000.00");
        assertThat(deliveries.get(id).retainedPayment()).isEqualByComparingTo("10000.00");
    }

    /**
     * Eight simultaneous requests with one key and one body create exactly one delivery: one reports "created", the
     * seven others return the very same delivery, stock is taken once, and only one key row exists.
     */
    @Test
    void sameKeyAndSameBodyCreatesOneDeliveryAndEveryoneSeesIt() throws Exception {
        var shop = shopWith(24 * 3, 2 * 3);
        String key = "key-" + UUID.randomUUID();
        List<Callable<CreateResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) tasks.add(() -> deliveries.create(request(shop, shop.packageA(), 1, "same"), owner, key));

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> o.value().created())).hasSize(1);
        assertThat(outcomes.stream().map(o -> o.value().delivery().id()).distinct()).hasSize(1);
        assertThat(deliveriesOf(shop)).isEqualTo(1);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(24 * 3 - 24);
        assertThat(jdbc.queryForObject("select count(*) from delivery_idempotency where idempotency_key = ?", Long.class, key)).isEqualTo(1);
    }

    /**
     * One key used at the same moment for two different orders: the first to arrive is created and the other is refused
     * (a key may not hide a different order). Which of the two wins varies, but never both, and never neither.
     */
    @Test
    void sameKeyWithDifferentBodiesLetsOneWinAndRefusesTheOther() throws Exception {
        var shop = shopWith(24 * 3, 2 * 3);
        String key = "key-" + UUID.randomUUID();
        List<Callable<CreateResult>> tasks = List.of(
                () -> deliveries.create(request(shop, shop.packageA(), 1, "alice"), owner, key),
                () -> deliveries.create(request(shop, shop.packageA(), 1, "bob"), owner, key));

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        onlyFailuresOfType(outcomes, RuleViolation.class);
        assertThat(deliveriesOf(shop)).isEqualTo(1);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(24 * 3 - 24);
    }

    /**
     * Keys belong to the user who sent them: two users sending the same key and body at the same moment each get their
     * own delivery, and neither sees the other's.
     */
    @Test
    void theSameKeyFromTwoUsersIsNotShared() throws Exception {
        var shop = shopWith(24 * 3, 2 * 3);
        long otherId = jdbc.queryForObject("""
                insert into app_user (username, password_hash, display_name, role, created_at)
                values (?, 'not-a-real-hash', 'Second', 'ASSISTANT', now()) returning id""", Long.class, "u-" + UUID.randomUUID());
        var other = new Actor(otherId, Role.ASSISTANT);
        String key = "key-" + UUID.randomUUID();
        List<Callable<CreateResult>> tasks = List.of(
                () -> deliveries.create(request(shop, shop.packageA(), 1, "same"), owner, key),
                () -> deliveries.create(request(shop, shop.packageA(), 1, "same"), other, key));

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).allMatch(o -> o.value().created());
        assertThat(outcomes.stream().map(o -> o.value().delivery().id()).distinct()).hasSize(2);
        assertThat(deliveriesOf(shop)).isEqualTo(2);
    }

    /**
     * Sixteen simultaneous purchases of 5 units at 12.50 leave exactly 80 units, an average of exactly 12.5000 and
     * 16 x 62.50 = 1,000.00 of cash out: no update was lost and every ledger row is accounted for.
     */
    @Test
    void concurrentPurchasesAtOneCostLoseNothing() throws Exception {
        var shop = shopWith(0, 0);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> {
                buy(shop.branchId(), shop.chicken(), 5, "12.50");
                return null;
            });
        }

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(80);
        assertThat(stock.averageCost(shop.chicken(), shop.branchId())).isEqualTo(new BigDecimal("12.5000"));
        assertThat(jdbc.queryForObject("select sum(quantity_delta) from stock_movement where product_id = ? and branch_id = ?",
                Long.class, shop.chicken(), shop.branchId())).isEqualTo(80);
        assertThat(jdbc.queryForObject("""
                select sum(e.amount) from financial_entry e join stock_movement m
                  on e.source_type = 'STOCK_MOVEMENT' and e.source_id = m.id
                 where m.product_id = ? and m.branch_id = ?""", BigDecimal.class, shop.chicken(), shop.branchId()))
                .isEqualByComparingTo("1000.00");
    }

    /**
     * Eight purchases of 10 @ 10.00 and eight of 10 @ 20.00 at once: the quantity is exact (160) whatever the order, and
     * the average is the true mean 15.00. Each step rounds to four decimals, so the order can move the stored value by
     * at most a few ten-thousandths; the test allows 0.0005 and documents that the result is not bit-for-bit order-free.
     */
    @Test
    void concurrentPurchasesAtDifferentCostsKeepTheQuantityExactAndTheAverageWithinRounding() throws Exception {
        var shop = shopWith(0, 0);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String cost = i % 2 == 0 ? "10.00" : "20.00";
            tasks.add(() -> {
                buy(shop.branchId(), shop.chicken(), 10, cost);
                return null;
            });
        }

        var outcomes = ConcurrentRunner.race(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(stock.balance(shop.chicken(), shop.branchId())).isEqualTo(160);
        assertThat(stock.averageCost(shop.chicken(), shop.branchId()).doubleValue()).isCloseTo(15.0, within(0.0005));
    }
}
