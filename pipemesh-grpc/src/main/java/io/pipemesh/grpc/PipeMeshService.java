package io.pipemesh.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.proto.v1.ExecutionHandle;
import io.pipemesh.proto.v1.ExecutionSnapshot;
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
     * Not implemented. Intent resolution decides which workflow to run, and until
     * that exists this would have to guess — which is precisely the thing the
     * design refuses to let a runtime do (§19, §20).
     */
    @Override
    public void processMessage(ProcessMessageRequest request, StreamObserver<ExecutionHandle> response) {
        response.onError(Status.UNIMPLEMENTED
                .withDescription("intent resolution is not implemented yet; name a workflow and use StartExecution")
                .asRuntimeException());
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

        subscription.set(broker.watch(executionId, update -> {
            if (call.isCancelled()) {
                return;
            }
            call.onNext(update);
            if (update.getUpdateCase() == ExecutionUpdate.UpdateCase.FINISHED) {
                close(subscription.get());
                call.onCompleted();
            }
        }));

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
