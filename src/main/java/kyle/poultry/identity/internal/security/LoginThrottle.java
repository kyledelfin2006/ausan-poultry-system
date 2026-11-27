package kyle.poultry.identity.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * In-memory limiter: after {@link #MAX_FAILURES} consecutive failures inside a {@link #WINDOW}, the key (lower-cased
 * username + remote address) is blocked until the window, which starts at the first failure, ends. A success resets it.
 * Thread-safe (one lock; operations are tiny) and bounded: expired entries are evicted, and at most {@link #MAX_KEYS}
 * keys are kept, dropping the oldest windows that are not blocked first. Internal to the identity module; used by {@code AuthController} only. State is
 * per application instance and is lost on restart.
 */
@Component
public class LoginThrottle {
    /** Failures allowed inside one window before the key is blocked. */
    public static final int MAX_FAILURES = 5;
    /** Length of the window, counted from the first failure. */
    static final Duration WINDOW = Duration.ofMinutes(15);
    /** Upper bound on tracked keys, so an attacker cannot exhaust memory with random usernames. */
    static final int MAX_KEYS = 10_000;

    /**
     * Failure count for one key.
     *
     * @param windowStart when the first failure of the current window happened
     * @param failures    number of failures in the current window
     */
    private record Attempts(Instant windowStart, int failures) {}

    private final Clock clock;
    /** Ordered by window start (a key is re-inserted when its window restarts), so eviction scans from the head. */
    private final LinkedHashMap<String, Attempts> entries = new LinkedHashMap<>();

    /**
     * Creates the throttle.
     *
     * @param clock source of "now"; tests supply a controllable clock
     */
    LoginThrottle(Clock clock) { this.clock = clock; }

    /**
     * Builds the throttle key for a login attempt. The key is a SHA-256 digest, so its size never depends on what the
     * caller submitted and an oversized username cannot pin memory.
     *
     * @param username      the submitted username (case and surrounding spaces are ignored)
     * @param remoteAddress the caller's address
     * @return the combined key
     */
    public static String key(String username, String remoteAddress) {
        return digest((username == null ? "" : username.strip().toLowerCase(Locale.ROOT)) + "|" + remoteAddress);
    }

    /**
     * Builds a key for another guarded action (for example changing a password), separate from login keys.
     *
     * @param action  a short action name
     * @param subject who or what is being protected (for example a user id)
     * @return the key
     */
    public static String key(String action, long subject) { return digest(action + "#" + subject); }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JVM must provide SHA-256
        }
    }

    /**
     * Atomically checks and reserves one attempt: if the key is blocked nothing changes; otherwise the attempt is
     * counted as a failure straight away. Call {@link #recordSuccess} when the attempt succeeds. Because the check and
     * the count happen under one lock, parallel requests cannot all pass the check before any is counted.
     *
     * @param key the key from {@link #key(String, String)}
     * @return the time left until attempts are allowed again, or null when the attempt was reserved
     */
    public synchronized Duration acquire(String key) {
        Duration wait = blockedFor(key);
        if (wait != null) return wait;
        // Fail closed: when the table is full of blocked keys a new key cannot be tracked, so it must not be let through
        // unlimited. Wait until the oldest block ends, which frees a slot.
        if (!track(key)) {
            Instant now = clock.instant();
            Instant frees = entries.values().iterator().next().windowStart().plus(WINDOW);
            Duration wait2 = Duration.between(now, frees);
            return wait2.isNegative() || wait2.isZero() ? Duration.ofSeconds(1) : wait2;
        }
        return null;
    }

    /**
     * Tells whether the key may try to log in now.
     *
     * @param key the key from {@link #key(String, String)}
     * @return the time left until attempts are allowed again, or null if this key may try now
     */
    synchronized Duration blockedFor(String key) {
        Instant now = clock.instant();
        evict(now);
        var a = entries.get(key);
        if (a == null || a.failures() < MAX_FAILURES) return null;
        return Duration.between(now, a.windowStart().plus(WINDOW));
    }

    /**
     * Counts one failed login for the key, starting a new window if there is none.
     *
     * @param key the key from {@link #key(String, String)}
     */
    synchronized void recordFailure(String key) { track(key); }

    /**
     * Counts one failure for the key. At the key cap the oldest key that is not blocked is dropped first, so a flood of
     * new keys cannot clear active blocks.
     *
     * @param key the key from {@link #key(String, String)}
     * @return false when the key is new, the table is full and every tracked key is blocked, so it could not be stored
     */
    private boolean track(String key) {
        Instant now = clock.instant();
        evict(now);
        var a = entries.get(key);
        if (a != null) {
            // Same window: keep its start so the block ends 15 minutes after the FIRST failure.
            entries.put(key, new Attempts(a.windowStart(), a.failures() + 1));
            return true;
        }
        if (entries.size() >= MAX_KEYS && !dropOldestUnblocked()) return false;
        entries.put(key, new Attempts(now, 1));
        return true;
    }

    /** {@return true if an unblocked key was removed, false when every tracked key is blocked} */
    private boolean dropOldestUnblocked() {
        for (var e : entries.entrySet()) {
            if (e.getValue().failures() < MAX_FAILURES) { entries.remove(e.getKey()); return true; }
        }
        return false;
    }

    /**
     * Forgets the key's failures after a successful login.
     *
     * @param key the key from {@link #key(String, String)}
     */
    public synchronized void recordSuccess(String key) { entries.remove(key); }

    /** {@return how many keys are currently tracked (for tests)} */
    synchronized int size() { return entries.size(); }

    /**
     * Removes windows that have ended. Entries are ordered by start time, so scanning stops at the first live one.
     *
     * @param now the current time
     */
    private void evict(Instant now) {
        Iterator<Map.Entry<String, Attempts>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().windowStart().plus(WINDOW).isAfter(now)) break;
            it.remove();
        }
    }
}
