package kyle.poultry.audit.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure unit tests of {@link AuditChanges}: no Spring, no database. */
class AuditChangesTests {

    /** Only fields whose value really changed are listed, in the order they were added. */
    @Test
    void listsOnlyChangedFields() {
        var changes = AuditChanges.of().field("name", "Small", "Small").field("price", 1, 2).field("active", true, false);
        assertThat(changes.isEmpty()).isFalse();
        assertThat(changes.summary()).isEqualTo("price: 1 -> 2; active: true -> false");
    }

    /** Nothing changed means nothing to record. */
    @Test
    void emptyWhenNothingChanged() {
        var changes = AuditChanges.of().field("a", "x", "x").field("b", null, null);
        assertThat(changes.isEmpty()).isTrue();
        assertThat(changes.summary()).isEmpty();
    }

    /** Decimals that differ only in scale are the same price; real changes print without exponent notation. */
    @Test
    void comparesDecimalsNumerically() {
        assertThat(AuditChanges.of().field("price", new BigDecimal("70000.0"), new BigDecimal("70000.00")).isEmpty()).isTrue();
        assertThat(AuditChanges.of().field("price", new BigDecimal("7E+4"), new BigDecimal("75000.50")).summary())
                .isEqualTo("price: 70000 -> 75000.50");
    }

    /** A value that appears or disappears is shown as null on the other side. */
    @Test
    void showsNullValues() {
        assertThat(AuditChanges.of().field("defaultPrice", null, new BigDecimal("5.00")).summary())
                .isEqualTo("defaultPrice: null -> 5.00");
    }
}
