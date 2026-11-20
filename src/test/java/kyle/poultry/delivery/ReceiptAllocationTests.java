package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kyle.poultry.catalog.CatalogApi.PackageItemView;
import kyle.poultry.catalog.CatalogApi.PackageView;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests of how {@link DeliveryReceiptService} splits one product's cost over the delivery lines that used
 * it. No Spring and no database: the rule is arithmetic, and the tricky part (centavo rounding) needs exact inputs.
 */
class ReceiptAllocationTests {

    /** Builds a package that holds {@code perPackage} units of product 1. */
    private static PackageView pack(long id, long perPackage) {
        return new PackageView(id, "SKU" + id, "Pack " + id, new BigDecimal("100.00"), true,
                List.of(new PackageItemView(1, "Chicken", perPackage)));
    }

    /** Builds a delivery line for a package. */
    private static DeliveryLine line(long packageId, long quantity) {
        return new DeliveryLine(packageId, "Pack " + packageId, quantity, new BigDecimal("100.00"),
                new BigDecimal("100.00").multiply(BigDecimal.valueOf(quantity)));
    }

    /** Creates the running per-line cost list the way the service does: all zeros. */
    private static List<BigDecimal> zeros(int n) {
        var list = new ArrayList<BigDecimal>();
        for (int i = 0; i < n; i++) list.add(BigDecimal.ZERO.setScale(2));
        return list;
    }

    /** A cost that does not divide evenly is rounded line by line and the last line takes the remainder. */
    @Test
    void lastLineTakesTheCentavoRemainder() {
        var lines = List.of(line(1, 1), line(2, 1), line(3, 1));
        var packages = Map.of(1L, pack(1, 1), 2L, pack(2, 1), 3L, pack(3, 1));
        var costs = zeros(3);

        DeliveryReceiptService.allocate(new BigDecimal("100.00"), 1, lines, packages, costs);

        // 100.00 / 3 = 33.33 for the first two lines; the third takes 33.34 so the lines add up exactly.
        assertThat(costs).containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
    }

    /** Units, not packages, decide the share: quantity x units per package. */
    @Test
    void sharesFollowTheUnitsEachLineUsed() {
        // Line 1: 2 packages x 1 unit = 2 units; line 2: 1 package x 3 units = 3 units.
        var lines = List.of(line(1, 2), line(2, 1));
        var packages = Map.of(1L, pack(1, 1), 2L, pack(2, 3));
        var costs = zeros(2);

        DeliveryReceiptService.allocate(new BigDecimal("50.00"), 1, lines, packages, costs);

        assertThat(costs).containsExactly(new BigDecimal("20.00"), new BigDecimal("30.00"));
    }

    /** A line whose package does not contain the product gets none of its cost. */
    @Test
    void linesThatDoNotUseTheProductPayNothing() {
        var other = new PackageView(2, "SKU2", "Other", new BigDecimal("1.00"), true, List.of(new PackageItemView(9, "Cage", 4)));
        var lines = List.of(line(1, 1), line(2, 5));
        var costs = zeros(2);

        DeliveryReceiptService.allocate(new BigDecimal("12.34"), 1, lines, Map.of(1L, pack(1, 2), 2L, other), costs);

        assertThat(costs).containsExactly(new BigDecimal("12.34"), new BigDecimal("0.00"));
    }

    /** Costs from several products accumulate on the same line. */
    @Test
    void costsAccumulateAcrossProducts() {
        var lines = List.of(line(1, 1));
        var costs = zeros(1);

        DeliveryReceiptService.allocate(new BigDecimal("10.00"), 1, lines, Map.of(1L, pack(1, 1)), costs);
        DeliveryReceiptService.allocate(new BigDecimal("5.50"), 1, lines, Map.of(1L, pack(1, 1)), costs);

        assertThat(costs).containsExactly(new BigDecimal("15.50"));
    }

    /** Rounding every line down and then topping up never produces a negative share: 0.02 over four lines. */
    @Test
    void tinyCostNeverGivesALineANegativeShare() {
        var lines = List.of(line(1, 1), line(2, 1), line(3, 1), line(4, 1));
        var packages = Map.of(1L, pack(1, 1), 2L, pack(2, 1), 3L, pack(3, 1), 4L, pack(4, 1));
        var costs = zeros(4);

        DeliveryReceiptService.allocate(new BigDecimal("0.02"), 1, lines, packages, costs);

        // Two centavos, four equal lines: the last two lines get one centavo each, none goes below zero.
        assertThat(costs).containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.01"), new BigDecimal("0.01"));
        assertThat(costs).allSatisfy(c -> assertThat(c.signum()).isGreaterThanOrEqualTo(0));
    }

    /** Leftover centavos go to the lines with the biggest remainder, and the shares always sum to the cost. */
    @Test
    void leftoverCentavosGoToTheLargestRemainders() {
        // Units 1, 2, 4 out of 7; cost 0.10 = 10 centavos: exact shares 1.43, 2.86, 5.71 -> floors 1, 2, 5 = 8, two left over.
        var lines = List.of(line(1, 1), line(2, 2), line(3, 4));
        var packages = Map.of(1L, pack(1, 1), 2L, pack(2, 1), 3L, pack(3, 1));
        var costs = zeros(3);

        DeliveryReceiptService.allocate(new BigDecimal("0.10"), 1, lines, packages, costs);

        // Remainders are 3/7, 6/7 and 5/7 of a centavo: the 2nd and 3rd lines receive the two extra centavos.
        assertThat(costs).containsExactly(new BigDecimal("0.01"), new BigDecimal("0.03"), new BigDecimal("0.06"));
        assertThat(costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.10");
    }
}
