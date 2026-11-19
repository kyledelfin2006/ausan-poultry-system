package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Unit tests of LoginThrottle with a controllable clock; no Spring context or database is needed. */
class LoginThrottleTests {

    /** A clock that only moves when the test calls advance, so time-based rules are tested without waiting. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-03-01T08:00:00Z");
        /** Returns UTC; the zone is irrelevant to the throttle. */
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        /** Returns this clock unchanged; zones are not used. */
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        /** Returns the current fake time. */
        @Override public Instant instant() { return now; }
        /** Moves the fake time forward by the given amount. */
        void advance(Duration d) { now = now.plus(d); }
    }

    final MutableClock clock = new MutableClock();
    final LoginThrottle throttle = new LoginThrottle(clock);

    /** Five failures block the key until 15 minutes after the first one, then attempts are allowed again. */
    @Test
    void blocksAfterFiveFailuresUntilTheWindowPasses() {
        String key = LoginThrottle.key("Maria", "10.0.0.1");
        for (int i = 0; i < 4; i++) { throttle.recordFailure(key); clock.advance(Duration.ofMinutes(1)); }
        assertThat(throttle.blockedFor(key)).isNull();
        throttle.recordFailure(key);                         // 5th failure, window started 4 minutes ago
        assertThat(throttle.blockedFor(key)).isEqualTo(Duration.ofMinutes(11));

        clock.advance(Duration.ofMinutes(10));
        assertThat(throttle.blockedFor(key)).isEqualTo(Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(1));
        assertThat(throttle.blockedFor(key)).isNull();
    }

    /** Keys ignore username case but are separate per address and per username. */
    @Test
    void keyIgnoresCaseAndIsPerAddress() {
        assertThat(LoginThrottle.key("MARIA", "10.0.0.1")).isEqualTo(LoginThrottle.key("maria", "10.0.0.1"));
        for (int i = 0; i < 5; i++) throttle.recordFailure(LoginThrottle.key("maria", "10.0.0.1"));
        assertThat(throttle.blockedFor(LoginThrottle.key("MARIA", "10.0.0.1"))).isNotNull();
        assertThat(throttle.blockedFor(LoginThrottle.key("maria", "10.0.0.2"))).isNull();
        assertThat(throttle.blockedFor(LoginThrottle.key("other", "10.0.0.1"))).isNull();
    }

    /** A success clears earlier failures, and failures older than the window do not add up. */
    @Test
    void successResetsTheCounterAndFailuresOutsideTheWindowDoNotCount() {
        String key = LoginThrottle.key("maria", "10.0.0.1");
        for (int i = 0; i < 4; i++) throttle.recordFailure(key);
        throttle.recordSuccess(key);
        throttle.recordFailure(key);
        assertThat(throttle.blockedFor(key)).isNull();

        for (int i = 0; i < 3; i++) throttle.recordFailure(key);   // 4 failures in this window
        clock.advance(LoginThrottle.WINDOW.plusSeconds(1));
        throttle.recordFailure(key);                                // fresh window: 1 failure
        assertThat(throttle.blockedFor(key)).isNull();
    }

    /** Expired entries are evicted and the number of tracked keys never exceeds the cap. */
    @Test
    void memoryIsBounded() {
        for (int i = 0; i < 50; i++) throttle.recordFailure(LoginThrottle.key("u" + i, "a"));
        assertThat(throttle.size()).isEqualTo(50);
        clock.advance(LoginThrottle.WINDOW.plusSeconds(1));
        throttle.blockedFor("anything");                            // any call evicts expired windows
        assertThat(throttle.size()).isZero();

        for (int i = 0; i < LoginThrottle.MAX_KEYS + 500; i++) throttle.recordFailure(LoginThrottle.key("u" + i, "a"));
        assertThat(throttle.size()).isEqualTo(LoginThrottle.MAX_KEYS);
    }
}
