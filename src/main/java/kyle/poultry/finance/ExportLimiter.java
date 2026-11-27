package kyle.poultry.finance;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import kyle.poultry.shared.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how many CSV exports run at once.
 *
 * <p>A streamed export keeps one database connection until the client has downloaded the whole file, which can be
 * minutes for a slow client, so a few slow downloads could use up the connection pool for everyone. Each export takes a
 * permit before streaming starts and gives it back when the stream ends, however it ends. When none is free the request
 * is refused at once (the status can still be chosen then) instead of waiting, because a waiting request would hold a
 * connection too.
 */
@Component
class ExportLimiter {
    /** Seconds a refused client is told to wait; an export of a normal period finishes well within this. */
    static final int RETRY_AFTER_SECONDS = 5;

    private final Semaphore permits;

    /**
     * Creates the limiter.
     *
     * @param maxConcurrent how many exports may run at once; keep it well below the connection pool size
     */
    ExportLimiter(@Value("${app.exports.max-concurrent:3}") int maxConcurrent) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("app.exports.max-concurrent must be at least 1");
        this.permits = new Semaphore(maxConcurrent);
    }

    /**
     * Takes a permit without waiting.
     *
     * @return the permit; call {@link Permit#release()} when the export ends (calling it again does nothing)
     * @throws TooManyRequestsException when all permits are in use
     */
    Permit acquire() {
        if (!permits.tryAcquire())
            throw new TooManyRequestsException("Too many exports are running; try again shortly", RETRY_AFTER_SECONDS);
        return new Permit(permits);
    }

    /** One taken permit; releasing it twice returns it only once. */
    static final class Permit {
        private final Semaphore owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore owner) { this.owner = owner; }

        /** Gives the permit back. */
        void release() {
            if (released.compareAndSet(false, true)) owner.release();
        }
    }
}
