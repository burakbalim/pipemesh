package io.pipemesh.core.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs {@link RecoverySweeper} on a schedule.
 *
 * <p>The sweeper existed before this and nothing called it, which made recovery
 * something an embedder had to remember. A runtime that survives a crashed
 * process only when someone remembers to ask does not survive a crashed process.
 *
 * <p>Deliberately simple: one virtual thread, a fixed delay, no coordination
 * between instances. Two runtimes sweeping at once is safe — the store's version
 * check settles it — but wasteful.
 *
 * <p>{@code ExecutionDispatcher.dispatchOnce()} fits {@link RecoveryPass} too, so
 * the same schedule can drive dispatch. That is the division of work between
 * instances the earlier note here was waiting for: a dispatcher claims before it
 * drives, so two of them on the same schedule take different executions rather
 * than the same one twice.
 */
public final class RecoveryScheduler implements AutoCloseable {

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final RecoveryPass pass;
    private final Duration interval;
    private final Consumer<Throwable> onFailure;
    private final ScheduledExecutorService clock =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    /** One pass over whatever is stuck. */
    @FunctionalInterface
    public interface RecoveryPass {
        int sweep();
    }

    public RecoveryScheduler(RecoverySweeper sweeper) {
        this(sweeper::sweep, DEFAULT_INTERVAL, failure -> {
        });
    }

    /**
     * @param onFailure told about a sweep that threw. A recovery loop that dies
     *                  quietly is worse than one that never ran: the executions
     *                  look attended to and are not.
     */
    public RecoveryScheduler(RecoverySweeper sweeper, Duration interval, Consumer<Throwable> onFailure) {
        this(sweeper::sweep, interval, onFailure);
    }

    public RecoveryScheduler(RecoveryPass pass, Duration interval, Consumer<Throwable> onFailure) {
        this.pass = Objects.requireNonNull(pass, "recovery pass");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.onFailure = Objects.requireNonNull(onFailure, "failure handler");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    public RecoveryScheduler start() {
        clock.scheduleWithFixedDelay(
                this::sweepQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    /**
     * A failing sweep must not end the schedule.
     *
     * <p>{@code scheduleWithFixedDelay} cancels the task when it throws, and a
     * database that was briefly unreachable would silently stop recovery for the
     * lifetime of the process.
     */
    private void sweepQuietly() {
        try {
            pass.sweep();
        } catch (RuntimeException failure) {
            onFailure.accept(failure);
        }
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
