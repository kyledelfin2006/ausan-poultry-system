package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.delivery.DeliveryService.CreateRequest;
import kyle.poultry.delivery.DeliveryService.LineRequest;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.error.ConflictException;
import kyle.poultry.shared.error.RuleViolation;
import kyle.poultry.stock.MovementType;
import kyle.poultry.stock.StockApi;
import kyle.poultry.stock.StockApi.StockCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Edge cases of delivery creation against a real database: requests that are legal for the validator but impossible for the
 * data (absurd quantities, products or branches switched off after the package was defined) must fail cleanly and write
 * nothing, and one branch's delivery must never touch another branch's stock.
 * <p>
 * "Fail cleanly" means three things in each test: an exception of a predictable kind, no delivery row, and no change to any
 * balance.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeliveryEdgeCaseTests {

    @Autowired DeliveryService deliveries;
    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private Actor owner;
    private long branchId;
    private long productId;
    private long packageId;

    /** A fresh branch, one product (stocked with 1,000 units) and a package of 4 of that product at 70,000.00, per test. */
    @BeforeEach
    void freshData() {
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class, TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        String tag = UUID.randomUUID().toString();
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        productId = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "p-" + tag);
        packageId = jdbc.queryForObject("insert into sales_package (sku, name, price) values (?, ?, 70000) returning id", Long.class, "S-" + tag, "S " + tag);
        jdbc.update("insert into package_item (package_id, product_id, quantity) values (?, ?, 4)", packageId, productId);
        stock.record(new StockCommand(productId, branchId, MovementType.PURCHASE, 1000, new BigDecimal("5.00"), null,
                LocalDate.now(clock), PaymentMethod.CASH), owner);
    }

    /**
     * Builds a one-line request from the test branch.
     *
     * @param quantity how many packages
     * @return the request
     */
    private CreateRequest request(long quantity) {
        return new CreateRequest("Juan", "Pedro", branchId, "Davao", null, PaymentMethod.CASH,
                List.of(new LineRequest(packageId, quantity, null)));
    }

    /** Asserts that nothing was written for the test branch: no delivery, and the stock is still the 1,000 bought. */
    private void assertNothingWritten() {
        assertThat(jdbc.queryForObject("select count(*) from delivery where from_branch_id = ?", Long.class, branchId)).isZero();
        assertThat(stock.balance(productId, branchId)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where product_id = ? and type = 'DELIVERY_OUT'",
                Long.class, productId)).isZero();
    }

    /**
     * A quantity that makes the total larger than the database can hold (and, multiplied by the package contents, would
     * wrap around a 64-bit integer: 4 x (2^62 + 1) = 2^64 + 4, which wraps to 4) is refused and leaves no trace. The
     * money column is the guard here, so the exception is the database's, which the API reports as a conflict.
     */
    @Test
    void anAbsurdQuantityIsRefusedAndWritesNothing() {
        long wrapsAroundWhenMultipliedByFour = (1L << 62) + 1;

        assertThatThrownBy(() -> deliveries.create(request(wrapsAroundWhenMultipliedByFour), owner))
                .isInstanceOfAny(DataIntegrityViolationException.class, RuleViolation.class, ConflictException.class);

        assertNothingWritten();
    }

    /** The largest quantity a client can send is refused the same way. */
    @Test
    void theLargestPossibleQuantityIsRefusedAndWritesNothing() {
        assertThatThrownBy(() -> deliveries.create(request(Long.MAX_VALUE), owner))
                .isInstanceOfAny(DataIntegrityViolationException.class, RuleViolation.class, ConflictException.class);

        assertNothingWritten();
    }

    /** A product switched off after the package was defined makes the delivery impossible: a rule violation, nothing written. */
    @Test
    void aSwitchedOffProductRefusesTheDeliveryAndWritesNothing() {
        jdbc.update("update product set active = false where id = ?", productId);

        assertThatThrownBy(() -> deliveries.create(request(1), owner)).isInstanceOf(RuleViolation.class);

        assertNothingWritten();
    }

    /** A branch switched off cannot ship, and the refusal comes before any stock moves. */
    @Test
    void aSwitchedOffBranchRefusesTheDeliveryAndWritesNothing() {
        jdbc.update("update branch set active = false where id = ?", branchId);

        assertThatThrownBy(() -> deliveries.create(request(1), owner)).isInstanceOf(RuleViolation.class);

        assertNothingWritten();
    }

    /** A delivery from one branch takes stock from that branch only, even when another branch holds the same product. */
    @Test
    void aDeliveryNeverTouchesAnotherBranchesStock() {
        long otherBranch = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "other-" + UUID.randomUUID());
        stock.record(new StockCommand(productId, otherBranch, MovementType.PURCHASE, 50, new BigDecimal("9.00"), null,
                LocalDate.now(clock), PaymentMethod.CASH), owner);

        deliveries.create(request(10), owner);

        assertThat(stock.balance(productId, branchId)).isEqualTo(1000 - 40);
        assertThat(stock.balance(productId, otherBranch)).isEqualTo(50);
        assertThat(stock.averageCost(productId, otherBranch)).isEqualTo(new BigDecimal("9.0000"));
    }
}
