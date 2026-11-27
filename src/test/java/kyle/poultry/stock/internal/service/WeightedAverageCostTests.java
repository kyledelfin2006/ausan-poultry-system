package kyle.poultry.stock.internal.service;

import kyle.poultry.stock.api.StockApi;
import kyle.poultry.stock.api.model.MovementType;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import kyle.poultry.TestcontainersConfiguration;
import kyle.poultry.finance.api.model.PaymentMethod;
import kyle.poultry.identity.api.model.Actor;
import kyle.poultry.identity.api.model.Role;
import kyle.poultry.shared.reference.SourceRef;
import kyle.poultry.stock.api.StockApi.StockCommand;
import kyle.poultry.stock.api.StockApi.StockLine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the weighted average cost against a PostgreSQL database, with the expected values worked out independently of
 * the production SQL.
 * <p>
 * The average is computed by one SQL statement ({@code StockBalanceRepository.applyDelta}), so a mock cannot test it.
 * Two kinds of test run here: hand-calculated scenarios that name the business rules (the average resets from an
 * empty shelf, free stock dilutes it, a rounding tie goes up), and a seeded random sequence of purchases, donations,
 * usage, adjustments and delivery stock-outs and reversals compared, step by step, with a simple {@link BigDecimal}
 * model of the same rule. After every step the stored balance must also equal the sum of the ledger.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WeightedAverageCostTests {

    @Autowired StockApi stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    /** The owner, who may enter every manual movement type. */
    private Actor owner;
    private long productId;
    private long branchId;
    private LocalDate today;

    /** A fresh product and branch per test, so no test sees another's stock. */
    @BeforeEach
    void freshData() {
        long ownerId = jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestcontainersConfiguration.OWNER_USERNAME);
        owner = new Actor(ownerId, Role.OWNER);
        String tag = UUID.randomUUID().toString();
        productId = jdbc.queryForObject("insert into product (name, unit) values (?, 'pc') returning id", Long.class, "p-" + tag);
        branchId = jdbc.queryForObject("insert into branch (name) values (?) returning id", Long.class, "b-" + tag);
        today = LocalDate.now(clock);
    }

    /**
     * Records a manual movement for the test product and branch.
     *
     * @param type   the movement type
     * @param delta  the signed quantity
     * @param cost   the unit cost as text, or null
     * @return the saved movement
     */
    private StockApi.MovementView record(MovementType type, long delta, String cost) {
        return stock.record(new StockCommand(productId, branchId, type, delta, cost == null ? null : new BigDecimal(cost),
                type.needsReason() ? "count" : null, today, type == MovementType.PURCHASE ? PaymentMethod.CASH : null), owner);
    }

    /**
     * Asserts the stored quantity and average cost.
     *
     * @param quantity the expected units on hand
     * @param average  the expected average cost, exactly four decimals
     */
    private void assertStock(long quantity, String average) {
        assertThat(stock.balance(productId, branchId)).as("quantity").isEqualTo(quantity);
        assertThat(stock.averageCost(productId, branchId)).as("average cost").isEqualTo(new BigDecimal(average));
    }

    /**
     * A day in the life of one product, calculated by hand:
     * <ol>
     *   <li>10 @ 100.00 gives an average of 100.0000;</li>
     *   <li>5 more @ 130.00: (10 x 100 + 5 x 130) / 15 = 110.0000;</li>
     *   <li>15 donated (cost 0): (15 x 110) / 30 = 55.0000, free stock dilutes the average;</li>
     *   <li>usage of 10 removes units but leaves the average at 55.0000;</li>
     *   <li>3 @ 99.99: (20 x 55 + 3 x 99.99) / 23 = 1399.97 / 23 = 60.868260... rounds to 60.8683;</li>
     *   <li>using all 23 leaves an empty shelf that still remembers 60.8683;</li>
     *   <li>4 @ 10.00 onto the empty shelf: the old average carries no weight, so it is exactly 10.0000;</li>
     *   <li>a positive adjustment of 6 has no cost of its own and leaves the average at 10.0000.</li>
     * </ol>
     */
    @Test
    void theAverageFollowsTheBusinessRulesStepByStep() {
        record(MovementType.PURCHASE, 10, "100.00");
        assertStock(10, "100.0000");
        record(MovementType.PURCHASE, 5, "130.00");
        assertStock(15, "110.0000");
        record(MovementType.DONATION, 15, null);
        assertStock(30, "55.0000");
        record(MovementType.USAGE, -10, null);
        assertStock(20, "55.0000");
        record(MovementType.PURCHASE, 3, "99.99");
        assertStock(23, "60.8683");
        record(MovementType.USAGE, -23, null);
        assertStock(0, "60.8683");
        record(MovementType.PURCHASE, 4, "10.00");
        assertStock(4, "10.0000");
        record(MovementType.ADJUSTMENT, 6, null);
        assertStock(10, "10.0000");
    }

    /**
     * A tie at the fifth decimal rounds up (half-up), not to even. 199 @ 0.01 then 1 @ 0.02 gives
     * (199 x 0.01 + 0.02) / 200 = 2.01 / 200 = 0.01005 exactly, which must be stored as 0.0101 (half-even would give
     * 0.0100).
     */
    @Test
    void aTieAtTheFifthDecimalRoundsUp() {
        record(MovementType.PURCHASE, 199, "0.01");
        assertStock(199, "0.0100");

        record(MovementType.PURCHASE, 1, "0.02");

        assertStock(200, "0.0101");
    }

    /** Stock-outs are costed at the average of that moment, and the reversal puts the goods back at that same cost. */
    @Test
    void aDeliveryStockOutIsCostedAtTheAverageAndItsReversalRestoresTheAverage() {
        record(MovementType.PURCHASE, 10, "10.00");
        record(MovementType.PURCHASE, 10, "20.00");
        assertStock(20, "15.0000");
        var source = new SourceRef("DELIVERY", 900_000 + new Random().nextInt(90_000));

        var out = stock.deduct(List.of(new StockLine(productId, 7)), branchId, source, today, owner);

        assertThat(out.getFirst().unitCost()).isEqualTo(new BigDecimal("15.0000"));
        assertThat(out.getFirst().totalCost()).isEqualTo(new BigDecimal("105.00"));
        assertStock(13, "15.0000");

        var back = stock.reverseAllFor(source, "cancelled", today, owner);

        assertThat(back).singleElement().satisfies(m -> assertThat(m.unitCost()).isEqualTo(new BigDecimal("15.0000")));
        assertStock(20, "15.0000");
    }

    /** The simple model of the rule, used as the oracle for the random test. */
    private record Model(long quantity, BigDecimal average) {

        /** The state before anything was recorded. */
        static Model empty() { return new Model(0, new BigDecimal("0.0000")); }

        /**
         * Stock comes in. With a cost, the average becomes the weighted mean rounded half-up to four decimals; without
         * one, the average is left alone.
         *
         * @param delta units added (positive)
         * @param cost  cost per unit, or null
         * @return the new state
         */
        Model in(long delta, BigDecimal cost) {
            if (cost == null) return new Model(quantity + delta, average);
            var weighted = average.multiply(BigDecimal.valueOf(quantity)).add(cost.multiply(BigDecimal.valueOf(delta)));
            var mean = weighted.divide(BigDecimal.valueOf(quantity + delta), MathContext.DECIMAL128);
            return new Model(quantity + delta, mean.setScale(4, RoundingMode.HALF_UP));
        }

        /**
         * Stock goes out; the average never changes on the way out.
         *
         * @param delta units removed (positive)
         * @return the new state
         */
        Model out(long delta) { return new Model(quantity - delta, average); }
    }

    /**
     * 300 random steps from a fixed seed (so a failure repeats). Each step is applied to the database and to the
     * model, and the quantity, the average and the ledger total must agree afterwards. The mix covers every way the
     * average can move: purchases (any cost), donations (free), usage and stock-outs (no change), adjustments of both
     * signs, reversals at the original cost, and draining to zero followed by a restart.
     */
    @Test
    void randomSequencesMatchTheModelAndTheLedgerStaysBalanced() {
        var random = new Random(20261120L);
        var model = Model.empty();
        for (int step = 1; step <= 300; step++) {
            int choice = random.nextInt(100);
            String what;
            if (choice < 35 || model.quantity() == 0 && choice < 85) {
                long qty = 1 + random.nextInt(50);
                var cost = new BigDecimal(random.nextInt(9_999) + 1).movePointLeft(2);   // 0.01 .. 99.99
                record(MovementType.PURCHASE, qty, cost.toPlainString());
                model = model.in(qty, cost);
                what = "purchase " + qty + " @ " + cost;
            } else if (choice < 45) {
                long qty = 1 + random.nextInt(20);
                record(MovementType.DONATION, qty, null);
                model = model.in(qty, BigDecimal.ZERO);
                what = "donation " + qty;
            } else if (choice < 60) {
                long qty = 1 + random.nextInt((int) Math.min(30, model.quantity()));
                record(MovementType.USAGE, -qty, null);
                model = model.out(qty);
                what = "usage " + qty;
            } else if (choice < 70) {
                boolean up = random.nextBoolean() || model.quantity() == 0;
                long qty = 1 + random.nextInt((int) Math.min(10, up ? 10 : model.quantity()));
                record(MovementType.ADJUSTMENT, up ? qty : -qty, null);
                model = up ? model.in(qty, null) : model.out(qty);
                what = "adjustment " + (up ? "+" : "-") + qty;
            } else if (choice < 85) {
                // Free opening-style stock is covered by donations; here a delivery takes stock out at the average.
                long qty = 1 + random.nextInt((int) Math.min(25, model.quantity()));
                var source = new SourceRef("DELIVERY", 1_000_000L + step);
                var out = stock.deduct(List.of(new StockLine(productId, qty)), branchId, source, today, owner);
                assertThat(out.getFirst().unitCost()).as("step %d stock-out cost", step).isEqualTo(model.average());
                var cost = out.getFirst().unitCost();
                model = model.out(qty);
                what = "delivery out " + qty;
                if (random.nextBoolean()) {
                    // Cancelled: the goods return at the cost they left with.
                    stock.reverseAllFor(source, "cancelled", today, owner);
                    model = model.in(qty, cost);
                    what += " then reversed";
                }
            } else {
                // Drain the shelf completely, so later purchases test the "average restarts from empty" rule.
                long qty = model.quantity();
                if (qty > 0) {
                    record(MovementType.USAGE, -qty, null);
                    model = model.out(qty);
                }
                what = "drain " + qty;
            }

            String label = "after step " + step + " (" + what + ")";
            assertThat(stock.balance(productId, branchId)).as(label + ": quantity").isEqualTo(model.quantity());
            assertThat(stock.averageCost(productId, branchId)).as(label + ": average").isEqualTo(model.average());
            long ledger = jdbc.queryForObject(
                    "select coalesce(sum(quantity_delta), 0) from stock_movement where product_id = ? and branch_id = ?",
                    Long.class, productId, branchId);
            assertThat(ledger).as(label + ": ledger total").isEqualTo(model.quantity());
        }
    }
}
