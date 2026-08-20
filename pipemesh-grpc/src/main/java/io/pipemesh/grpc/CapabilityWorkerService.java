package io.pipemesh.grpc;

import io.grpc.stub.StreamObserver;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.proto.v1.CapabilityInvocation;
import io.pipemesh.proto.v1.CapabilityWorkerGrpc;
import io.pipemesh.proto.v1.WorkerMessage;

import java.util.Set;
import java.util.UUID;

/**
 * The half of the boundary that runs the other way (§26.1).
 *
 * <p>The worker opens the connection and the runtime pushes invocations down it,
 * so a worker needs no reachable address, certificate or firewall exception. That
 * is what lets business code stay where it was written — in someone's
 * application, in their language — while a workflow still names it as a
 * capability and learns nothing else about it (§9.8).
 */
public final class CapabilityWorkerService extends CapabilityWorkerGrpc.CapabilityWorkerImplBase {

    private final WorkerRegistry workers;

    public CapabilityWorkerService(WorkerRegistry workers) {
        this.workers = workers;
    }

    @Override
    public StreamObserver<WorkerMessage> connect(StreamObserver<CapabilityInvocation> invocations) {
        String workerId = UUID.randomUUID().toString();

        return new StreamObserver<>() {

            @Override
            public void onNext(WorkerMessage message) {
                switch (message.getMessageCase()) {
                    case REGISTRATION -> workers.register(new ConnectedWorker(
                            workerId,
                            organizationOf(message),
                            Set.copyOf(message.getRegistration().getCapabilityIdsList()),
                            invocations));
                    case RESULT -> workers.find(workerId).ifPresent(worker ->
                            worker.answer(message.getResult().getInvocationId(), resultOf(message)));
                    default -> {
                        // A message shape this runtime does not know is not worth
                        // dropping a working worker over.
                    }
                }
            }

            @Override
            public void onError(Throwable failure) {
                workers.unregister(workerId, "disconnected");
            }

            @Override
            public void onCompleted() {
                workers.unregister(workerId, "hung up");
                invocations.onCompleted();
            }
        };
    }

    private String organizationOf(WorkerMessage message) {
        String organization = message.getRegistration().getOrganizationId();
        return organization.isBlank() ? "default" : organization;
    }

    private CapabilityResult resultOf(WorkerMessage message) {
        var result = message.getResult();
        if (result.hasFailure()) {
            return new CapabilityResult.Failure(
                    result.getFailure().getCode(),
                    result.getFailure().getMessage(),
                    result.getFailure().getRetryable());
        }
        return new CapabilityResult.Success(JsonStructs.toJson(result.getOutput()));
    }
}
