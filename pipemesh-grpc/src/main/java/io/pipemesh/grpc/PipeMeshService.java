package io.pipemesh.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.OrganizationMismatchException;
import io.pipemesh.core.execution.ProcessRequest;
import io.pipemesh.core.intent.IntentUnresolvedException;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.WorkflowRuntime;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;
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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
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
    private final PrincipalResolver principals;
    private final List<String> watchPermissions;

    public PipeMeshService(WorkflowRuntime runtime, ExecutionUpdateBroker broker) {
        this(runtime, broker, PrincipalResolver.ANONYMOUS);
    }

    /**
     * @param principals how a caller's identity is established. Without one, every
     *                   remote caller is anonymous and holds no permissions —
     *                   capabilities that ask for none still work, the rest are
     *                   refused (§23).
     */
    public PipeMeshService(
            WorkflowRuntime runtime, ExecutionUpdateBroker broker, PrincipalResolver principals) {

        this(runtime, broker, principals, List.of());
    }

    /**
     * @param watchPermissions what a caller must hold to watch an execution live.
     *                         Empty — the default — means watching is open, which
     *                         is what a deployment that identifies nobody can
     *                         enforce anyway (§22.2). A deployment that sells live
     *                         watching as a feature names a permission here, and
     *                         decides elsewhere who gets it.
     */
    public PipeMeshService(
            WorkflowRuntime runtime, ExecutionUpdateBroker broker,
            PrincipalResolver principals, List<String> watchPermissions) {

        this.watchPermissions = List.copyOf(
                Objects.requireNonNull(watchPermissions, "watch permissions"));
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.principals = Objects.requireNonNull(principals, "principal resolver");
    }

    @Override
    public void startExecution(StartExecutionRequest request, StreamObserver<ExecutionHandle> response) {
        answer(response, () -> WireTypes.toWire(runtime.start(new ExecutionRequest(
                WorkflowId.of(request.getWorkflowId()),
                new ExecutionInput(JsonStructs.toJson(request.getInput())),
                organizationOf(request.getOrganizationId()),
                request.getTraceparent(),
                null,
                caller(),
                versionOf(request.getWorkflowVersion())))));
    }

    /** An empty version on the wire means "newest", not a version named "". */
    private static WorkflowVersion versionOf(String version) {
        return version == null || version.isBlank() ? null : WorkflowVersion.of(version);
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
                    request.getTraceparent(),
                    caller()))));
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
        answer(response, () -> WireTypes.toWire(scoped().resume(
                ExecutionId.of(request.getExecutionId()),
                new ResumeSignal.Approval(
                        request.getApprovalId(),
                        request.getApproved(),
                        request.getDecidedBy(),
                        request.getComment()),
                caller())));
    }

    @Override
    public void getExecution(GetExecutionRequest request, StreamObserver<ExecutionSnapshot> response) {
        answer(response, () -> scoped().snapshot(ExecutionId.of(request.getExecutionId()), caller())
                .map(WireTypes::toWire)
                .orElseThrow(() -> new NoSuchElementException(
                        "no execution " + request.getExecutionId())));
    }

    /**
     * The runtime, when it can answer on a caller's behalf.
     *
     * <p>Isolation needs a caller, and only the default implementation takes one.
     * A custom {@link WorkflowRuntime} that does not is served unscoped — its
     * author owns that decision, and pretending to enforce something this cannot
     * see would be worse.
     */
    private DefaultWorkflowRuntime scoped() {
        if (runtime instanceof DefaultWorkflowRuntime scoped) {
            return scoped;
        }
        throw new UnsupportedOperationException(
                "this runtime cannot answer on a caller's behalf, so isolation cannot be enforced");
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
        Principal caller = caller();
        if (!mayWatch(caller)) {
            // Refused rather than answered with an empty stream. A stream that
            // opens and closes looks exactly like "nothing is happening", which
            // is indistinguishable from a broken workflow — a feature that is off
            // has to say so.
            response.onError(Status.PERMISSION_DENIED
                    .withDescription("watching an execution requires " + watchPermissions)
                    .asRuntimeException());
            return;
        }

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

        unsubscribe.set(broker.watch(executionId, Set.copyOf(request.getExcludeList()), pump::offer));
        call.setOnCancelHandler(() -> close(subscription.get()));
    }

    /**
     * Whether live watching is switched on for this caller.
     *
     * <p>Not a new mechanism: the permission set a {@code Principal} already
     * carries (§23), now with a second reader. Which permissions an API key's
     * principal holds is a deployment's business — a subscription plan, a
     * directory, a static config — and none of it reaches the engine.
     *
     * <p>{@code missingFrom} is what makes an unrestricted principal exempt
     * without a special case, and an empty requirement open to everyone. Off by
     * default matters: a permission demanded of callers nobody authenticated
     * would deny every existing deployment in the name of a rule none of them
     * asked for.
     */
    private boolean mayWatch(Principal caller) {
        return caller.missingFrom(watchPermissions).isEmpty();
    }

    private void close(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // Unsubscribing cannot fail in a way the caller can act on.
        }
    }

    /**
     * Who is calling, according to the resolver — never according to the request.
     *
     * <p>Read from the call's own metadata, which the client cannot set to
     * anything the resolver does not accept.
     */
    private Principal caller() {
        return principals.resolve(CallMetadata.current());
    }

    /**
     * Which organization the work belongs to.
     *
     * <p>The caller's, whenever anybody established one. The field on the request
     * is a convenience for deployments that identify nobody — where it is also the
     * only thing available, and where there is no isolation to undermine (§22.2).
     */
    private OrganizationId organizationOf(String requested) {
        return caller().organizationIfKnown().orElseGet(() ->
                requested == null || requested.isBlank()
                        ? OrganizationId.DEFAULT
                        : OrganizationId.of(requested));
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
        } catch (OrganizationMismatchException notTheirs) {
            response.onError(Status.PERMISSION_DENIED
                    .withDescription(notTheirs.getMessage()).asRuntimeException());
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
