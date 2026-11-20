package kyle.poultry.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs several tasks at the same moment so a test can provoke a real race, and reports each task's result or failure.
 * <p>
 * Every task waits at a start gate until all of them are ready, so they hit the database together instead of one after
 * another. Waits are bounded: a task that never finishes (a deadlock, for example) fails the test with a clear message
 * instead of hanging the build. A task's exception is never swallowed; it is returned so the test can assert exactly
 * which failures are acceptable.
 */
public final class ConcurrentRunner {

    /** How long all tasks may take to get ready and to finish; far above a healthy run, far below the CI timeout. */
    private static final long LIMIT_SECONDS = 60;

    /** Not instantiable: static helper only. */
    private ConcurrentRunner() {}

    /**
     * What one task did.
     *
     * @param value the task's result, or null if it failed
     * @param error the exception it threw, or null if it succeeded
     * @param <T>   the result type
     */
    public record Outcome<T>(T value, Throwable error) {

        /** {@return true when the task finished without throwing} */
        public boolean succeeded() { return error == null; }
    }

    /**
     * Starts all tasks together and waits for every one of them.
     *
     * @param tasks the tasks to race; each runs on its own thread
     * @param <T>   the result type
     * @return one outcome per task, in the order the tasks were given
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws AssertionError       if the tasks do not all become ready, or one does not finish in time (a likely deadlock)
     */
    public static <T> List<Outcome<T>> race(List<Callable<T>> tasks) throws InterruptedException {
        var pool = Executors.newFixedThreadPool(tasks.size());
        try {
            var ready = new CountDownLatch(tasks.size());
            var go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    // Tell the test thread this worker is waiting, then wait for the shared starting signal.
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            if (!ready.await(LIMIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("Tasks did not all start");
            // Everyone is parked at the gate: release them together.
            go.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(LIMIT_SECONDS, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    // The task's own exception, not the executor's wrapper.
                    outcomes.add(new Outcome<>(null, e.getCause()));
                } catch (TimeoutException e) {
                    throw new AssertionError("A task did not finish within " + LIMIT_SECONDS + "s (deadlock?)", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
