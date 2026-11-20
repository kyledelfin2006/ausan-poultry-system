package kyle.poultry.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import kyle.poultry.finance.PaymentMethod;
import kyle.poultry.shared.ConflictException;
import kyle.poultry.shared.RuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure unit tests of the {@link Delivery} lifecycle: ACTIVE may become DONE or CANCELLED, and both of those are final.
 * <p>
 * No Spring and no database: the entity owns its transition rules, so they can be proved by calling it directly.
 * The stock and money effects of a transition belong to {@code DeliveryService} and are tested there.
 */
class DeliveryStateMachineTests {

    /** The moment the trip starts in every test. */
    private static final Instant STARTED = Instant.parse("2026-11-20T01:00:00Z");

    /** "Now" for every test: two hours after the start. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    /**
     * Builds a fresh ACTIVE delivery with one line of two packages at 1,000.00.
     *
     * @return the new delivery
     */
    private static Delivery active() {
        var line = new DeliveryLine(10, "Package A", 2, new BigDecimal("1000.00"), new BigDecimal("2000.00"));
        return Delivery.open("DLV-2026-0001", "Juan", "Pedro", 1, "Tarlac", STARTED, PaymentMethod.CASH,
                List.of(line), 1, STARTED);
    }

    /**
     * Builds a delivery that already reached a final state.
     *
     * @param finalState DONE or CANCELLED
     * @return a delivery in that state
     */
    private static Delivery inState(DeliveryStatus finalState) {
        var d = active();
        if (finalState == DeliveryStatus.DONE) d.markDone(NOW, NOW);
        else d.cancel("changed mind", NOW);
        return d;
    }

    /** A new delivery is ACTIVE, has no completion or cancellation data, and its total is the sum of its lines. */
    @Test
    void openStartsActiveWithTheTotalOfItsLines() {
        var lines = List.of(
                new DeliveryLine(10, "Package A", 2, new BigDecimal("1000.00"), new BigDecimal("2000.00")),
                new DeliveryLine(11, "Package B", 1, new BigDecimal("500.50"), new BigDecimal("500.50")));

        var d = Delivery.open("DLV-2026-0002", "Juan", "Pedro", 1, "Tarlac", STARTED, PaymentMethod.GCASH, lines, 7, STARTED);

        assertThat(d.status()).isEqualTo(DeliveryStatus.ACTIVE);
        // 2 x 1000.00 + 1 x 500.50, worked out by hand rather than by the production code.
        assertThat(d.total()).isEqualByComparingTo("2500.50");
        assertThat(d.completedAt()).isNull();
        assertThat(d.cancelReason()).isNull();
        assertThat(d.createdBy()).isEqualTo(7);
    }

    /** Marking an ACTIVE delivery done records the arrival time and nothing else. */
    @Test
    void markDoneMovesActiveToDoneAndRecordsTheArrival() {
        var d = active();

        d.markDone(NOW, NOW);

        assertThat(d.status()).isEqualTo(DeliveryStatus.DONE);
        assertThat(d.completedAt()).isEqualTo(NOW);
        assertThat(d.cancelReason()).isNull();
    }

    /** Arrival is allowed exactly at the start and exactly now: both boundaries are inclusive. */
    @Test
    void completionBoundariesAreInclusive() {
        var atStart = active();
        atStart.markDone(STARTED, NOW);
        var atNow = active();
        atNow.markDone(NOW, NOW);

        assertThat(atStart.status()).isEqualTo(DeliveryStatus.DONE);
        assertThat(atNow.status()).isEqualTo(DeliveryStatus.DONE);
    }

    /** One millisecond outside either boundary is refused, and a refused attempt leaves the delivery ACTIVE. */
    @Test
    void completionOutsideTheAllowedWindowIsRefusedAndChangesNothing() {
        var d = active();

        assertThatThrownBy(() -> d.markDone(STARTED.minusMillis(1), NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("before the start");
        assertThatThrownBy(() -> d.markDone(NOW.plusMillis(1), NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("in the future");
        assertThatThrownBy(() -> d.markDone(null, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("required");

        assertThat(d.status()).isEqualTo(DeliveryStatus.ACTIVE);
        assertThat(d.completedAt()).isNull();
    }

    /** Cancelling an ACTIVE delivery keeps the reason it was given. */
    @Test
    void cancelMovesActiveToCancelledAndKeepsTheReason() {
        var d = active();

        d.cancel("Customer cancelled", NOW);

        assertThat(d.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(d.cancelReason()).isEqualTo("Customer cancelled");
        assertThat(d.completedAt()).isNull();
    }

    /**
     * A cancellation without a real reason is refused and the delivery stays ACTIVE, because the database also
     * requires a reason for every cancelled row.
     *
     * @param reason a missing or blank reason
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "   \n"})
    void cancelWithoutAReasonIsRefused(String reason) {
        var d = active();

        assertThatThrownBy(() -> d.cancel(reason, NOW))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("reason");

        assertThat(d.status()).isEqualTo(DeliveryStatus.ACTIVE);
        assertThat(d.cancelReason()).isNull();
    }

    /**
     * Every transition out of a final state is a conflict, whichever final state it is. The state check comes before
     * the input check, so even a call with invalid input reports the conflict.
     *
     * @param name       what is attempted, for the test report
     * @param transition the attempted transition
     * @param from       the final state the delivery is in
     */
    @ParameterizedTest(name = "{0} from {2}")
    @MethodSource("transitionsOutOfFinalStates")
    void finalStatesRefuseEveryTransition(String name, Consumer<Delivery> transition, DeliveryStatus from) {
        var d = inState(from);

        assertThatThrownBy(() -> transition.accept(d))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DLV-2026-0001")
                .hasMessageContaining(from.name());

        // The refused call must not have changed anything.
        assertThat(d.status()).isEqualTo(from);
    }

    /**
     * The attempts to leave DONE or CANCELLED: a valid one and an invalid one for each transition.
     *
     * @return (name, transition, state the delivery is in) triples
     */
    static Stream<Arguments> transitionsOutOfFinalStates() {
        Stream.Builder<Arguments> all = Stream.builder();
        for (var from : List.of(DeliveryStatus.DONE, DeliveryStatus.CANCELLED)) {
            all.add(Arguments.of("mark done", (Consumer<Delivery>) d -> d.markDone(NOW, NOW), from));
            all.add(Arguments.of("mark done with null time", (Consumer<Delivery>) d -> d.markDone(null, NOW), from));
            all.add(Arguments.of("cancel", (Consumer<Delivery>) d -> d.cancel("again", NOW), from));
            all.add(Arguments.of("cancel with no reason", (Consumer<Delivery>) d -> d.cancel(null, NOW), from));
        }
        return all.build();
    }
}
