package kyle.poultry.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.stock.StockApi.SourceRef;
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

    /** Fresh product + branch per test, so tests never see each other's stock. */
    @BeforeEach
    void freshData() {
        ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        String tag = UUID.randomUUID().toString();
        productId = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "p1-" + tag);
        product2Id = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "p2-" + tag);
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
    }

    StockCommand cmd(long product, MovementType type, long delta) {
        return new StockCommand(product, branchId, type, delta, null, type.needsReason() ? "test" : null, today);
    }

    @Test
    void balanceFollowsMovementsAndHistoryExplainsIt() {
        stock.record(cmd(productId, MovementType.OPENING, 50), owner);
        stock.record(cmd(productId, MovementType.PURCHASE, 100), owner);
        stock.record(cmd(productId, MovementType.USAGE, -20), owner);

        assertThat(stock.balance(productId, branchId)).isEqualTo(130);
        assertThat(jdbc.queryForObject("select sum(quantity_delta) from stock_movement where product_id=? and branch_id=?",
                Long.class, productId, branchId)).isEqualTo(130);
    }

    @Test
    void cannotDeductMoreThanAvailable_andNothingIsWritten() {
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);

        assertThatThrownBy(() -> stock.deduct(List.of(new StockLine(productId, 11)), branchId, new SourceRef("DELIVERY", 1), today, owner))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stock.balance(productId, branchId)).isEqualTo(10);
        assertThat(count("stock_movement")).isEqualTo(1);   // only the opening
    }

    @Test
    void multiLineDeductIsAllOrNothing() {
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);
        stock.record(cmd(product2Id, MovementType.OPENING, 1), owner);

        assertThatThrownBy(() -> stock.deduct(
                List.of(new StockLine(productId, 5), new StockLine(product2Id, 2)), branchId, new SourceRef("DELIVERY", 2), today, owner))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stock.balance(productId, branchId)).isEqualTo(10);
        assertThat(stock.balance(product2Id, branchId)).isEqualTo(1);
    }

    @Test
    void twoConcurrentDeliveriesCannotBothTakeTheLastStock() throws Exception {
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> deliver = () -> {
                start.await();
                try {
                    stock.deduct(List.of(new StockLine(productId, 6)), branchId, new SourceRef("DELIVERY", System.nanoTime()), today, owner);
                    return true;
                } catch (InsufficientStockException e) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(deliver);
            Future<Boolean> b = pool.submit(deliver);
            start.countDown();
            assertThat(Stream.of(a.get(), b.get()).filter(ok -> ok).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock.balance(productId, branchId)).isEqualTo(4);
    }

    @Test
    void reversalRestoresStockOnceEvenIfRequestedTwice() {
        stock.record(cmd(productId, MovementType.OPENING, 10), owner);
        var source = new SourceRef("DELIVERY", 99);
        stock.deduct(List.of(new StockLine(productId, 7)), branchId, source, today, owner);
        assertThat(stock.balance(productId, branchId)).isEqualTo(3);

        assertThat(stock.reverseAllFor(source, "cancelled", today, owner)).hasSize(1);
        assertThat(stock.reverseAllFor(source, "cancelled", today, owner)).isEmpty();

        assertThat(stock.balance(productId, branchId)).isEqualTo(10);
        assertThat(count("stock_movement")).isEqualTo(3);   // opening, out, reversal: original rows kept
    }

    @Test
    void movementSignRulesAreEnforcedInJavaAndInTheDatabase() {
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.PURCHASE, -5), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.USAGE, 5), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.DELIVERY_OUT, -5), owner)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> stock.record(new StockCommand(productId, branchId, MovementType.USAGE, -1, null, null, today), owner))
                .isInstanceOf(RuleViolation.class);   // usage needs a reason

        // Bypass Java entirely: the database refuses a negative purchase too.
        assertThatThrownBy(() -> jdbc.update("""
                insert into stock_movement (product_id, branch_id, type, quantity_delta, occurred_on, created_by, created_at)
                values (?, ?, 'PURCHASE', -5, current_date, ?, now())""", productId, branchId, ownerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assistantCannotEnterOpeningStockOrAdjustments() {
        var assistant = new Actor(ownerId, Role.ASSISTANT);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.OPENING, 5), assistant)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> stock.record(cmd(productId, MovementType.ADJUSTMENT, 5), assistant)).isInstanceOf(AccessDeniedException.class);
        stock.record(cmd(productId, MovementType.PURCHASE, 5), assistant);   // routine entries are allowed
    }

    @Test
    void futureDatesAreRejected() {
        var future = new StockCommand(productId, branchId, MovementType.PURCHASE, 5, null, null, today.plusDays(1));
        assertThatThrownBy(() -> stock.record(future, owner)).isInstanceOf(RuleViolation.class);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where product_id in (?, ?) and branch_id = ?",
                Long.class, productId, product2Id, branchId);
    }
}
