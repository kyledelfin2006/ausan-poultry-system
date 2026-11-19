package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.delivery.DeliveryService.CreateRequest;
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.ConflictException;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.stock.InsufficientStockException;
import kyle.poultry.stock.MovementType;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.StockCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Service-level tests of delivery creation, completion, cancellation and refunds against a real Postgres, calling
 * DeliveryService directly with explicit actors. Each test builds its own branch, products and package so tests are
 * independent.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeliveryTests {

    @Autowired DeliveryService deliveries;
    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;

    long ownerId, branchId, chicken, cage, smallPackage;
    Actor owner, assistant;
    LocalDate today = LocalDate.now();

    /** Fresh branch, two products and a package (24 chickens + 2 cages, PHP 70,000) per test, so tests are isolated. */
    @BeforeEach
    void freshData() {
        ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        assistant = new Actor(ownerId, Role.ASSISTANT);
        String tag = UUID.randomUUID().toString();
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        chicken = jdbc.queryForObject("insert into product (name, unit) values (?, 'head') returning id", Long.class, "chicken-" + tag);
        cage = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "cage-" + tag);
        smallPackage = jdbc.queryForObject(
                "insert into sales_package (sku, name, price) values (?, ?, 70000) returning id", Long.class, "SKU-" + tag, "Small " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 24), (?, ?, 2)",
                smallPackage, chicken, smallPackage, cage);
        buy(chicken, 100, "10.00");
        buy(cage, 10, "50.00");
    }

    /**
     * Buys stock into the test branch at a unit cost, as the owner.
     *
     * @param product product id
     * @param qty units
     * @param unitCost cost per unit
     */
    void buy(long product, long qty, String unitCost) {
        stock.record(new StockCommand(product, branchId, MovementType.PURCHASE, qty, new BigDecimal(unitCost),
                null, today, PaymentMethod.CASH), owner);
    }

    /**
     * Builds a create request for the test package.
     *
     * @param overridePrice price override or null for the package price
     * @param packages number of packages
     * @return the request
     */
    CreateRequest request(BigDecimal overridePrice, long packages) {
        return new CreateRequest("Juan Dela Cruz", "Pedro", branchId, "Davao City", null, PaymentMethod.GCASH,
                List.of(new LineRequest(smallPackage, packages, overridePrice)));
    }

    /**
     * Sums the ledger entries of one type for a delivery.
     *
     * @param type financial entry type
     * @param deliveryId the delivery
     * @return the total, zero when none
     */
    BigDecimal sum(String type, long deliveryId) {
        return jdbc.queryForObject("select coalesce(sum(amount),0) from financial_entry where type=? and source_type='DELIVERY' and source_id=?",
                BigDecimal.class, type, deliveryId);
    }

    /**
     * Counts all rows in a table.
     *
     * @param table table name (a constant from the test, never user input)
     * @return the row count
     */
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }

    /** Creating a delivery takes the package contents out of stock and records sale, cost of goods and payment. */
    @Test
    void createDeductsThePackageContentsAndRecordsSaleCostAndPayment() {
        var d = deliveries.create(request(null, 2), assistant);

        assertThat(d.status()).isEqualTo(DeliveryStatus.ACTIVE);
        assertThat(d.code()).matches("DLV-\\d{4}-\\d{4,}");
        assertThat(d.total()).isEqualByComparingTo("140000.00");
        assertThat(d.lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(2);
            assertThat(l.unitPrice()).isEqualByComparingTo("70000.00");
        });

        assertThat(stock.balance(chicken, branchId)).isEqualTo(100 - 48);
        assertThat(stock.balance(cage, branchId)).isEqualTo(10 - 4);

        assertThat(sum("SALE", d.id())).isEqualByComparingTo("140000.00");
        assertThat(sum("PAYMENT_IN", d.id())).isEqualByComparingTo("140000.00");
        assertThat(sum("COST_OF_GOODS", d.id())).isEqualByComparingTo("680.00");   // 48 x 10 + 4 x 50
    }

    /** A price override applies to that delivery only; the catalog price is unchanged. */
    @Test
    void priceCanBeOverriddenForOneDeliveryWithoutChangingThePackage() {
        var d = deliveries.create(request(new BigDecimal("65000.00"), 1), assistant);

        assertThat(d.total()).isEqualByComparingTo("65000.00");
        assertThat(jdbc.queryForObject("select price from sales_package where id = ?", BigDecimal.class, smallPackage))
                .isEqualByComparingTo("70000.00");
    }

    /** Insufficient stock rolls back everything: no delivery, no ledger entry, no stock change. */
    @Test
    void notEnoughStockRejectsTheWholeDeliveryAndLeavesNoTrace() {
        long deliveriesBefore = count("delivery");
        long entriesBefore = count("financial_entry");

        assertThatThrownBy(() -> deliveries.create(request(null, 5), assistant))   // needs 120 chickens, only 100 held
                .isInstanceOf(InsufficientStockException.class);

        assertThat(count("delivery")).isEqualTo(deliveriesBefore);
        assertThat(count("financial_entry")).isEqualTo(entriesBefore);
        assertThat(stock.balance(chicken, branchId)).isEqualTo(100);
        assertThat(stock.balance(cage, branchId)).isEqualTo(10);
    }

    /**
     * Blank names, no lines, duplicate or zero-quantity lines and future start times are refused without touching
     * stock.
     */
    @Test
    void invalidRequestsAreRejected() {
        var line = List.of(new LineRequest(smallPackage, 1, null));
        assertThatThrownBy(() -> deliveries.create(new CreateRequest(" ", "Pedro", branchId, "x", null, PaymentMethod.CASH, line), assistant))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> deliveries.create(new CreateRequest("A", "B", branchId, "x", null, PaymentMethod.CASH, List.of()), assistant))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> deliveries.create(new CreateRequest("A", "B", branchId, "x", null, PaymentMethod.CASH,
                List.of(new LineRequest(smallPackage, 1, null), new LineRequest(smallPackage, 1, null))), assistant))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> deliveries.create(new CreateRequest("A", "B", branchId, "x", null, PaymentMethod.CASH,
                List.of(new LineRequest(smallPackage, 0, null))), assistant)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> deliveries.create(new CreateRequest("A", "B", branchId, "x", Instant.now().plusSeconds(3600),
                PaymentMethod.CASH, line), assistant)).isInstanceOf(RuleViolation.class);
        assertThat(stock.balance(chicken, branchId)).isEqualTo(100);
    }

    /** Marking done changes status only, with no stock or money effect, and is final. */
    @Test
    void markingDoneRecordsCompletionOnlyAndIsFinal() {
        var d = deliveries.create(request(null, 1), assistant);
        long movements = count("stock_movement");
        long entries = count("financial_entry");

        var done = deliveries.markDone(d.id(), Instant.now(), assistant);

        assertThat(done.status()).isEqualTo(DeliveryStatus.DONE);
        assertThat(done.completedAt()).isNotNull();
        assertThat(count("stock_movement")).isEqualTo(movements);       // no extra stock effect
        assertThat(count("financial_entry")).isEqualTo(entries);        // no extra sale

        assertThatThrownBy(() -> deliveries.markDone(d.id(), Instant.now(), assistant)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> deliveries.cancel(d.id(), "too late", owner)).isInstanceOf(ConflictException.class);
    }

    /** A completion time before the start or in the future is refused and the delivery stays ACTIVE. */
    @Test
    void completionCannotBeBeforeStartOrInTheFuture() {
        var d = deliveries.create(request(null, 1), assistant);
        assertThatThrownBy(() -> deliveries.markDone(d.id(), d.startedAt().minusSeconds(60), assistant)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> deliveries.markDone(d.id(), Instant.now().plusSeconds(3600), assistant)).isInstanceOf(RuleViolation.class);
        assertThat(deliveries.get(d.id()).status()).isEqualTo(DeliveryStatus.ACTIVE);
    }

    /**
     * Cancelling (owner only) restores stock, adds reversal entries, keeps the originals and shows the payment as
     * retained.
     */
    @Test
    void cancellingRestoresStockReversesSaleAndCostAndKeepsThePaymentUntilRefunded() {
        var d = deliveries.create(request(null, 2), assistant);

        assertThatThrownBy(() -> deliveries.cancel(d.id(), "customer changed mind", assistant)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> deliveries.cancel(d.id(), " ", owner)).isInstanceOf(RuleViolation.class);

        var cancelled = deliveries.cancel(d.id(), "customer changed mind", owner);

        assertThat(cancelled.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(stock.balance(chicken, branchId)).isEqualTo(100);
        assertThat(stock.balance(cage, branchId)).isEqualTo(10);
        assertThat(sum("SALE", d.id())).isEqualByComparingTo("140000.00");            // originals retained
        assertThat(sum("SALE_REVERSAL", d.id())).isEqualByComparingTo("140000.00");
        assertThat(sum("COGS_REVERSAL", d.id())).isEqualByComparingTo("680.00");
        assertThat(cancelled.retainedPayment()).isEqualByComparingTo("140000.00");     // paid, not yet refunded

        assertThatThrownBy(() -> deliveries.cancel(d.id(), "again", owner)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where branch_id = ?", Long.class, branchId))
                .isEqualTo(2 + 2 + 2);   // 2 purchases, 2 stock-outs, 2 reversals; nothing doubled
    }

    /** Refunds need an owner, a CANCELLED delivery and an amount within what was paid. */
    @Test
    void refundsAreOwnerOnlyCancelledOnlyAndCappedByThePayment() {
        var d = deliveries.create(request(null, 1), assistant);

        assertThatThrownBy(() -> deliveries.refund(d.id(), new BigDecimal("100"), PaymentMethod.CASH, null, owner))
                .isInstanceOf(RuleViolation.class);   // still ACTIVE

        deliveries.cancel(d.id(), "cancelled", owner);

        assertThatThrownBy(() -> deliveries.refund(d.id(), new BigDecimal("100"), PaymentMethod.CASH, null, assistant))
                .isInstanceOf(AccessDeniedException.class);

        var partial = deliveries.refund(d.id(), new BigDecimal("50000.00"), PaymentMethod.GCASH, "partial", owner);
        assertThat(partial.retainedPayment()).isEqualByComparingTo("20000.00");

        assertThatThrownBy(() -> deliveries.refund(d.id(), new BigDecimal("20000.01"), PaymentMethod.GCASH, null, owner))
                .isInstanceOf(RuleViolation.class);

        var full = deliveries.refund(d.id(), new BigDecimal("20000.00"), PaymentMethod.GCASH, null, owner);
        assertThat(full.retainedPayment()).isEqualByComparingTo("0");
    }
}
