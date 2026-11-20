package kyle.poultry.stock;

import static kyle.poultry.support.TestSupport.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import kyle.poultry.identity.Actor;
import kyle.poultry.identity.Role;
import kyle.poultry.shared.RuleViolation;
import kyle.poultry.shared.SourceRef;
import kyle.poultry.stock.StockApi.MovementView;
import kyle.poultry.stock.StockApi.StockCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure unit tests of the stock movement rules that need no database: which sign each type allows, when a reason or a
 * cost is required, how costs are rounded, and what a reversal looks like.
 * <p>
 * The same sign rules also exist as a database check constraint; that second line of defence is proved in
 * {@code StockLedgerTests} against a real PostgreSQL.
 */
class StockMovementTests {

    /** The business date used by every command. */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    /** When the movement is created. */
    private static final Instant NOW = Instant.parse("2026-11-20T02:15:00Z");

    /** The user recording the movements. */
    private static final Actor OWNER = new Actor(1, Role.OWNER);

    /**
     * Builds a command for product 5 at branch 2 with the given type, quantity, cost and reason.
     *
     * @param type   the movement type
     * @param delta  the signed quantity
     * @param cost   the unit cost, or null
     * @param reason the reason, or null
     * @return the command, dated {@link #DAY}
     */
    private static StockCommand cmd(MovementType type, long delta, String cost, String reason) {
        return new StockCommand(5, 2, type, delta, cost == null ? null : new BigDecimal(cost), reason, DAY, null);
    }

    /**
     * Which signs each type accepts, written out by hand from the business rules: purchases, openings, donations and
     * reversals only add; usage and delivery stock-outs only remove; an adjustment may do either; nothing is zero.
     *
     * @param type     the movement type
     * @param delta    the signed quantity under test
     * @param accepted whether the type must accept that quantity
     */
    @ParameterizedTest(name = "{0} with {1} -> accepted: {2}")
    @CsvSource({
            "OPENING,5,true", "OPENING,-5,false", "OPENING,0,false",
            "PURCHASE,5,true", "PURCHASE,-5,false", "PURCHASE,0,false",
            "DONATION,5,true", "DONATION,-5,false", "DONATION,0,false",
            "DELIVERY_REVERSAL,5,true", "DELIVERY_REVERSAL,-5,false", "DELIVERY_REVERSAL,0,false",
            "USAGE,5,false", "USAGE,-5,true", "USAGE,0,false",
            "DELIVERY_OUT,5,false", "DELIVERY_OUT,-5,true", "DELIVERY_OUT,0,false",
            "ADJUSTMENT,5,true", "ADJUSTMENT,-5,true", "ADJUSTMENT,0,false"})
    void eachTypeAcceptsOnlyItsOwnDirection(MovementType type, long delta, boolean accepted) {
        assertThat(type.accepts(delta)).isEqualTo(accepted);
    }

    /** Only usage and adjustments change stock without a purchase or delivery to explain them, so only they need a reason. */
    @ParameterizedTest
    @EnumSource(MovementType.class)
    void onlyUsageAndAdjustmentNeedAReason(MovementType type) {
        boolean expected = type == MovementType.USAGE || type == MovementType.ADJUSTMENT;

        assertThat(type.needsReason()).isEqualTo(expected);
    }

    /** A person may enter five of the seven types; the owner alone may enter two of those. */
    @Test
    void manualAndOwnerOnlyTypeSetsMatchTheBusinessRules() {
        assertThat(MovementType.MANUAL).containsExactlyInAnyOrder(
                MovementType.OPENING, MovementType.PURCHASE, MovementType.DONATION, MovementType.USAGE, MovementType.ADJUSTMENT);
        assertThat(MovementType.OWNER_ONLY).containsExactlyInAnyOrder(MovementType.OPENING, MovementType.ADJUSTMENT);
    }

    /** An illegal sign is refused before anything could be saved. */
    @Test
    void createRefusesAnIllegalSign() {
        assertThatThrownBy(() -> StockMovement.create(cmd(MovementType.PURCHASE, -3, "10", null), null, OWNER, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("does not allow a quantity of -3");
    }

    /**
     * Usage and adjustments cannot be recorded without a real reason.
     *
     * @param type   a type that needs a reason
     * @param reason a missing or blank reason
     */
    @ParameterizedTest
    @CsvSource(value = {"USAGE,-1,", "USAGE,-1,'  '", "ADJUSTMENT,2,", "ADJUSTMENT,-2,''"})
    void createRefusesMissingReasonsWhereOneIsRequired(MovementType type, long delta, String reason) {
        assertThatThrownBy(() -> StockMovement.create(cmd(type, delta, null, reason), null, OWNER, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("requires a reason");
    }

    /** A negative cost is never valid; free stock is recorded with a zero cost (or as a donation), not a negative one. */
    @Test
    void createRefusesANegativeCost() {
        assertThatThrownBy(() -> StockMovement.create(cmd(MovementType.OPENING, 5, "-0.0001", null), null, OWNER, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("negative");
    }

    /** A purchase needs a cost above zero: no cost, or a zero cost, would silently dilute the average cost. */
    @Test
    void createRefusesPurchasesWithoutARealCost() {
        assertThatThrownBy(() -> StockMovement.create(cmd(MovementType.PURCHASE, 5, null, null), null, OWNER, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("requires a unit cost");
        assertThatThrownBy(() -> StockMovement.create(cmd(MovementType.PURCHASE, 5, "0.00", null), null, OWNER, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("DONATION");
    }

    /** Every movement needs a business date, because reports and history are keyed by it. */
    @Test
    void createRefusesAMissingDate() {
        var noDate = new StockCommand(5, 2, MovementType.OPENING, 5, null, null, null, null);

        assertThatThrownBy(() -> StockMovement.create(noDate, null, OWNER, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("Date is required");
    }

    /**
     * A cost is stored to four decimals, half-up, matching the {@code numeric(14,4)} column. The inputs sit on both
     * sides of the rounding boundary so a truncating implementation would fail.
     *
     * @param given    the cost the caller sent
     * @param expected the cost that must be stored
     */
    @ParameterizedTest
    @CsvSource({"12.34564,12.3456", "12.34565,12.3457", "12.34566,12.3457", "20,20.0000", "0.00005,0.0001", "0.00004,0.0000"})
    void costIsRoundedHalfUpToFourDecimals(String given, String expected) {
        var m = StockMovement.create(cmd(MovementType.OPENING, 5, given, null), null, OWNER, NOW);

        // The scale matters too: the ledger always stores exactly four decimals.
        assertThat(m.unitCost()).isEqualTo(new BigDecimal(expected));
    }

    /** Opening stock and donations are free by default; they take no cost unless the caller says so. */
    @ParameterizedTest
    @EnumSource(value = MovementType.class, names = {"OPENING", "DONATION"})
    void freeTypesDefaultToAZeroCost(MovementType type) {
        var m = StockMovement.create(cmd(type, 5, null, null), null, OWNER, NOW);

        assertThat(m.unitCost()).isEqualTo(new BigDecimal("0.0000"));
    }

    /** Usage has no cost of its own; it stays empty until the ledger stamps it with the average cost at that moment. */
    @Test
    void usageStartsWithoutACostAndIsStampedOnce() {
        var m = StockMovement.create(cmd(MovementType.USAGE, -2, null, "fed to birds"), null, OWNER, NOW);
        assertThat(m.unitCost()).isNull();

        m.stampCostIfMissing(new BigDecimal("11.2500"));
        assertThat(m.unitCost()).isEqualTo(new BigDecimal("11.2500"));

        // A cost that is already there is never overwritten by a later average.
        m.stampCostIfMissing(new BigDecimal("99.0000"));
        assertThat(m.unitCost()).isEqualTo(new BigDecimal("11.2500"));
    }

    /** A purchase keeps its own cost: the stamp only fills a gap. */
    @Test
    void purchaseKeepsItsOwnCostWhenStamped() {
        var m = StockMovement.create(cmd(MovementType.PURCHASE, 4, "10", null), null, OWNER, NOW);

        m.stampCostIfMissing(new BigDecimal("55.0000"));

        assertThat(m.unitCost()).isEqualTo(new BigDecimal("10.0000"));
    }

    /** The source reference and the actor are copied onto the row, and a manual movement has no source. */
    @Test
    void createCopiesTheSourceAndTheActor() {
        var fromDelivery = StockMovement.create(cmd(MovementType.DELIVERY_OUT, -3, null, null),
                new SourceRef("DELIVERY", 77), new Actor(9, Role.ASSISTANT), NOW);
        var manual = StockMovement.create(cmd(MovementType.OPENING, 3, null, null), null, OWNER, NOW);

        var view = withId(fromDelivery, 1).view();
        assertThat(view.sourceType()).isEqualTo("DELIVERY");
        assertThat(view.sourceId()).isEqualTo(77);
        assertThat(view.createdBy()).isEqualTo(9);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(withId(manual, 2).view().sourceType()).isNull();
    }

    /**
     * A reversal is the exact opposite of the original: same product, branch, source and cost, opposite sign, and a
     * link back. The original row is left exactly as it was.
     */
    @Test
    void reversalMirrorsTheOriginalAndLeavesItUntouched() {
        var original = withId(StockMovement.create(cmd(MovementType.DELIVERY_OUT, -7, "4.2500", null),
                new SourceRef("DELIVERY", 77), OWNER, NOW), 41);
        var later = Instant.parse("2026-11-21T01:00:00Z");

        var reversal = withId(original.reversal("Delivery cancelled", DAY.plusDays(1), new Actor(2, Role.OWNER), later), 42);

        assertThat(reversal.type()).isEqualTo(MovementType.DELIVERY_REVERSAL);
        assertThat(reversal.quantityDelta()).isEqualTo(7);
        assertThat(reversal.unitCost()).isEqualTo(new BigDecimal("4.2500"));
        var view = reversal.view();
        assertThat(view.productId()).isEqualTo(5);
        assertThat(view.branchId()).isEqualTo(2);
        assertThat(view.reversesId()).isEqualTo(41);
        assertThat(view.sourceType()).isEqualTo("DELIVERY");
        assertThat(view.sourceId()).isEqualTo(77);
        assertThat(view.occurredOn()).isEqualTo(DAY.plusDays(1));
        assertThat(view.createdBy()).isEqualTo(2);
        // Append-only: nothing about the original changed.
        assertThat(original.quantityDelta()).isEqualTo(-7);
        assertThat(original.type()).isEqualTo(MovementType.DELIVERY_OUT);
        assertThat(original.view().reversesId()).isNull();
    }

    /**
     * The cost of a movement is |quantity| x unit cost rounded half-up to centavos. The cases include the ties and
     * near-ties where a different rounding mode or rounding before multiplying would give another answer.
     *
     * @param quantity the signed quantity
     * @param unitCost the cost per unit
     * @param expected the expected value at 2 decimals
     */
    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({
            "-7,12.3456,86.42",     // 86.4192
            "3,0.3333,1.00",        // 0.9999
            "1,0.0050,0.01",        // exact tie, rounds up
            "2,0.0025,0.01",        // 0.0050, tie again
            "1,0.0049,0.00",        // just below the tie
            "-10,3.3333,33.33",     // 33.333
            "100,0.0000,0.00"})
    void totalCostIsTheAbsoluteQuantityTimesTheUnitCostRoundedToCentavos(long quantity, String unitCost, String expected) {
        var view = new MovementView(1, 5, 2, MovementType.ADJUSTMENT, quantity, new BigDecimal(unitCost), null, DAY,
                null, null, null, 1, NOW);

        assertThat(view.totalCost()).isEqualTo(new BigDecimal(expected));
    }

    /** A movement with no recorded cost counts as zero pesos instead of failing. */
    @Test
    void totalCostOfAMovementWithoutACostIsZero() {
        var view = new MovementView(1, 5, 2, MovementType.USAGE, -3, null, "x", DAY, null, null, null, 1, NOW);

        assertThat(view.totalCost()).isEqualTo(new BigDecimal("0.00"));
    }
}
