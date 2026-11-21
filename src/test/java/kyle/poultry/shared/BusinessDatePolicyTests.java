package kyle.poultry.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BusinessDatePolicyTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), ZoneId.of("Asia/Manila"));
    private final LocalDate today = LocalDate.now(clock);

    @Test
    void assistantsMayPostThroughTheInclusiveSevenDayBoundary() {
        assertThatCode(() -> BusinessDatePolicy.requireAllowed(today, false, clock)).doesNotThrowAnyException();
        assertThatCode(() -> BusinessDatePolicy.requireAllowed(today.minusDays(7), false, clock)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BusinessDatePolicy.requireAllowed(today.minusDays(8), false, clock))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("7 days");
    }

    @Test
    void ownersMayBackdateWithoutALowerBoundButNobodyMayPostInTheFuture() {
        assertThatCode(() -> BusinessDatePolicy.requireAllowed(today.minusYears(10), true, clock)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BusinessDatePolicy.requireAllowed(today.plusDays(1), true, clock))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("future");
        assertThatThrownBy(() -> BusinessDatePolicy.requireAllowed(today.plusDays(1), false, clock))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("future");
    }

    @Test
    void missingDatesAreRejected() {
        assertThatThrownBy(() -> BusinessDatePolicy.requireAllowed(null, false, clock))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("required");
    }
}
