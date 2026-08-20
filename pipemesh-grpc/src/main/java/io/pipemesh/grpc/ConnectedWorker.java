package io.pipemesh.grpc;

import io.grpc.stub.StreamObserver;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.proto.v1.CapabilityInvocation;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One worker's open stream, and the calls it is currently holding.
 *
 * <p>Sends are synchronized because a worker's stream has many callers — several
 * executions can invoke the same worker at once — and gRPC allows exactly one
 * writer. This is the same rule that {@code UpdatePump} exists for, arriving from
 * the other direction.
 *
 * <p>An answer finds its question through {@code invocationId}: the worker may
 * reply in any order, and often will.
 */
final class ConnectedWorker {

    private final String workerId;
    private final String organization;
    private final Set<String> capabilities;
    private final StreamObserver<CapabilityInvocation> stream;
    private final Map<String, CompletableFuture<CapabilityResult>> waiting = new ConcurrentHashMap<>();

    ConnectedWorker(
            String workerId,
            String organization,
            Set<String> capabilities,
            StreamObserver<CapabilityInvocation> stream) {

        this.workerId = workerId;
        this.organization = organization;
        this.capabilities = Set.copyOf(capabilities);
        this.stream = stream;
    }

    String workerId() {
        return workerId;
    }

    String organization() {
        return organization;
    }

    boolean serves(String capabilityId) {
        return capabilities.contains(capabilityId);
    }

    CapabilityResult invoke(CapabilityInvocation invocation, Duration deadline) {
        CompletableFuture<CapabilityResult> answer = new CompletableFuture<>();
        waiting.put(invocation.getInvocationId(), answer);

        try {
            synchronized (stream) {
                stream.onNext(invocation);
            }
            return answer.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            return new CapabilityResult.Failure("worker.timeout",
                    "worker " + workerId + " did not answer within " + deadline.toMillis() + "ms",
                    true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CapabilityResult.Failure("worker.interrupted", "the runtime is shutting down", false);
        } catch (RuntimeException | java.util.concurrent.ExecutionException failure) {
            return new CapabilityResult.Failure("worker.unreachable", String.valueOf(failure), true);
        } finally {
            waiting.remove(invocation.getInvocationId());
        }
    }

    void answer(String invocationId, CapabilityResult result) {
        Optional.ofNullable(waiting.remove(invocationId))
                .ifPresent(answer -> answer.complete(result));
    }

    /**
     * Fails everything this worker was holding.
     *
     * <p>{@code retryable} is false: the worker took the call and then died, and
     * nothing on this side knows whether it ran. Deciding that for the caller is
     * exactly the guess that bills a customer twice — the capability's own
     * idempotency declaration is what allows a retry, and that is checked further
     * up.
     */
    void abandon(String reason) {
        waiting.forEach((invocationId, answer) -> answer.complete(new CapabilityResult.Failure(
                "worker.died", "worker " + workerId + " " + reason + " while holding this call", false)));
        waiting.clear();
    }
}
