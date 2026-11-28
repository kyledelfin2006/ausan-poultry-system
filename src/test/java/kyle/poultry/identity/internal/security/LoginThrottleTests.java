package kyle.poultry.identity.internal.security;

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

    /** The key is a fixed-size digest, so an oversized username cannot make the tracked key large. */
    @Test
    void keyHasAFixedSizeWhateverTheUsernameLength() {
        assertThat(LoginThrottle.key("x".repeat(5_000_000), "10.0.0.1")).hasSize(64);
        assertThat(LoginThrottle.key("a", "10.0.0.1")).hasSize(64);
        assertThat(LoginThrottle.key("password-change", 7)).hasSize(64).isNotEqualTo(LoginThrottle.key("password-change", 8));
    }

    /** Parallel attempts cannot all pass the check: exactly the allowed number are let through. */
    @Test
    void acquireLetsThroughExactlyTheAllowedAttemptsUnderParallelLoad() throws Exception {
        String key = LoginThrottle.key("maria", "10.0.0.1");
        int threads = 64;
        var start = new java.util.concurrent.CountDownLatch(1);
        var allowed = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) futures.add(pool.submit(() -> {
                start.await();
                if (throttle.acquire(key) == null) allowed.incrementAndGet();
                return null;
            }));
            start.countDown();
            for (var f : futures) f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(allowed.get()).isEqualTo(LoginThrottle.MAX_FAILURES);
        assertThat(throttle.blockedFor(key)).isNotNull();
    }

    /** A success after acquire clears the reserved attempt. */
    @Test
    void acquireThenSuccessLeavesNoFailureBehind() {
        String key = LoginThrottle.key("maria", "10.0.0.1");
        for (int i = 0; i < 4; i++) assertThat(throttle.acquire(key)).isNull();
        throttle.recordSuccess(key);
        for (int i = 0; i < 5; i++) assertThat(throttle.acquire(key)).isNull();
        assertThat(throttle.acquire(key)).isNotNull();
    }

    /** Flooding the throttle with new keys must not clear a key that is already blocked. */
    @Test
    void floodOfNewKeysDoesNotEvictABlockedKey() {
        String blocked = LoginThrottle.key("victim", "10.0.0.1");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) throttle.recordFailure(blocked);
        for (int i = 0; i < LoginThrottle.MAX_KEYS + 500; i++) throttle.recordFailure(LoginThrottle.key("u" + i, "a"));

        assertThat(throttle.size()).isEqualTo(LoginThrottle.MAX_KEYS);
        assertThat(throttle.blockedFor(blocked)).isNotNull();
    }

    /** When every tracked key is blocked, a new key is refused instead of being evicted and left unlimited. */
    @Test
    void aFullTableOfBlockedKeysFailsClosedForNewKeys() {
        for (int i = 0; i < LoginThrottle.MAX_KEYS; i++) {
            String key = LoginThrottle.key("u" + i, "a");
            for (int f = 0; f < LoginThrottle.MAX_FAILURES; f++) throttle.recordFailure(key);
        }
        String fresh = LoginThrottle.key("newcomer", "b");

        for (int i = 0; i < 20; i++) assertThat(throttle.acquire(fresh)).as("attempt " + i).isNotNull();
        assertThat(throttle.size()).isEqualTo(LoginThrottle.MAX_KEYS);

        // Once the blocks expire there is room again and the key is limited normally.
        clock.advance(LoginThrottle.WINDOW.plusSeconds(1));
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) assertThat(throttle.acquire(fresh)).isNull();
        assertThat(throttle.acquire(fresh)).isNotNull();
    }
}
