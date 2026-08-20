package io.pipemesh.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ProcessRequest;
import io.pipemesh.core.intent.IntentUnresolvedException;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.proto.v1.ExecutionHandle;
import io.pipemesh.proto.v1.ExecutionSnapshot;
import io.pipemesh.proto.v1.ExecutionStarted;
import io.pipemesh.proto.v1.ExecutionUpdate;
import io.pipemesh.proto.v1.GetExecutionRequest;
import io.pipemesh.proto.v1.PipeMeshGrpc;
import io.pipemesh.proto.v1.ProcessMessageRequest;
import io.pipemesh.proto.v1.StartExecutionRequest;
import io.pipemesh.proto.v1.SubmitApprovalRequest;
import io.pipemesh.proto.v1.WatchExecutionRequest;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The runtime, reachable from any language.
 *
 * <p>A thin adapter and nothing more: every method translates a request, calls
 * one runtime method and translates the answer. There is no decision in this
 * class, because a decision here would be a decision an in-process caller does
 * not get — and the two must not drift (§26.1).
 */
public final class PipeMeshService extends PipeMeshGrpc.PipeMeshImplBase {

    private final WorkflowRuntime runtime;
    private final ExecutionUpdateBroker broker;

    public PipeMeshService(WorkflowRuntime runtime, ExecutionUpdateBroker broker) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    @Override
    public void startExecution(StartExecutionRequest request, StreamObserver<ExecutionHandle> response) {
        answer(response, () -> WireTypes.toWire(runtime.start(new ExecutionRequest(
                WorkflowId.of(request.getWorkflowId()),
                new ExecutionInput(JsonStructs.toJson(request.getInput())),
                organizationOf(request.getOrganizationId()),
                request.getTraceparent()))));
    }

    /**
     * Reads the message, then runs whatever workflow it asked for (§19).
     *
     * <p>A message that does not settle on an intent answers
     * {@code FAILED_PRECONDITION} rather than {@code INVALID_ARGUMENT}: the
     * request was well formed, the runtime simply could not tell what to do with
     * it — and a client that cannot see that difference will retry the wrong
     * things.
     */
    @Override
    public void processMessage(ProcessMessageRequest request, StreamObserver<ExecutionHandle> response) {
        try {
            response.onNext(WireTypes.toWire(runtime.process(new ProcessRequest(
                    request.getMessage(),
                    new ExecutionInput(JsonStructs.toJson(request.getInput())),
                    organizationOf(request.getOrganizationId()),
                    request.getTraceparent()))));
            response.onCompleted();
        } catch (IntentUnresolvedException unresolved) {
            response.onError(Status.FAILED_PRECONDITION
                    .withDescription(unresolved.getMessage()).asRuntimeException());
        } catch (RuntimeException failure) {
            response.onError(Status.INTERNAL.withDescription(failure.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void submitApproval(SubmitApprovalRequest request, StreamObserver<ExecutionHandle> response) {
        answer(response, () -> WireTypes.toWire(runtime.resume(
                ExecutionId.of(request.getExecutionId()),
                new ResumeSignal.Approval(
                        request.getApprovalId(),
                        request.getApproved(),
                        request.getDecidedBy(),
                        request.getComment()))));
    }

    @Override
    public void getExecution(GetExecutionRequest request, StreamObserver<ExecutionSnapshot> response) {
        answer(response, () -> runtime.snapshot(ExecutionId.of(request.getExecutionId()))
                .map(WireTypes::toWire)
                .orElseThrow(() -> new NoSuchElementException(
                        "no execution " + request.getExecutionId())));
    }

    /**
     * Streams what happens to an execution until it ends or the caller goes away.
     *
     * <p>The stream closes itself once the execution reaches a terminal status.
     * A watcher should not have to know when to stop reading, and a client left
     * blocked on an execution that finished ten minutes ago is a bug that looks
     * like a hang.
     *
     * <p>The subscription is also dropped on cancellation, so a client that
     * disconnects does not leave the broker feeding a stream nobody reads.
     */
    @Override
    public void watchExecution(WatchExecutionRequest request, StreamObserver<ExecutionUpdate> response) {
        ExecutionId executionId = ExecutionId.of(request.getExecutionId());
        ServerCallStreamObserver<ExecutionUpdate> call = (ServerCallStreamObserver<ExecutionUpdate>) response;
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        AtomicReference<AutoCloseable> unsubscribe = new AtomicReference<>(() -> {
        });

        // Sequence 0 is where things stood when this watcher arrived. Without it a
        // client has no moment it can point at and say "from here I am listening",
        // and anything that happens between asking to watch and being subscribed
        // is lost with no way to notice.
        runtime.snapshot(executionId).ifPresent(snapshot -> call.onNext(ExecutionUpdate.newBuilder()
                .setSequence(0)
                .setAt(WireTypes.toWire(System.currentTimeMillis()))
                .setStarted(ExecutionStarted.newBuilder()
                        .setExecution(WireTypes.toWire(snapshot))
                        .build())
                .build()));

        UpdatePump pump = new UpdatePump(call);
        subscription.set(() -> {
            pump.close();
            unsubscribe.get().close();
        });

        unsubscribe.set(broker.watch(executionId, pump::offer));
        call.setOnCancelHandler(() -> close(subscription.get()));
    }

    private void close(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // Unsubscribing cannot fail in a way the caller can act on.
        }
    }

    private OrganizationId organizationOf(String id) {
        return id == null || id.isBlank() ? OrganizationId.DEFAULT : OrganizationId.of(id);
    }

    /**
     * Maps the runtime's failures onto the status codes a client can act on.
     *
     * <p>A missing workflow is the caller's mistake, not the server's, and a
     * client that cannot tell them apart retries the wrong things.
     */
    private <T> void answer(StreamObserver<T> response, ThrowingSupplier<T> work) {
        try {
            response.onNext(work.get());
            response.onCompleted();
        } catch (NoSuchElementException missing) {
            response.onError(Status.NOT_FOUND.withDescription(missing.getMessage()).asRuntimeException());
        } catch (IllegalArgumentException malformed) {
            response.onError(Status.INVALID_ARGUMENT
                    .withDescription(malformed.getMessage()).asRuntimeException());
        } catch (RuntimeException failure) {
            response.onError(Status.INTERNAL.withDescription(failure.getMessage()).asRuntimeException());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
