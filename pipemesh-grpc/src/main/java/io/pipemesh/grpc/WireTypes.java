package io.pipemesh.grpc;

import com.google.protobuf.Timestamp;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionSnapshot;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.proto.v1.StepOutcome;

/**
 * Translates between the runtime's types and the wire's.
 *
 * <p>Only translation lives here. The moment this file starts deciding anything,
 * the gRPC service has stopped being an adapter and become a second
 * implementation with its own opinions (§26.1).
 */
final class WireTypes {

    private WireTypes() {
    }

    static io.pipemesh.proto.v1.ExecutionStatus toWire(ExecutionStatus status) {
        return switch (status) {
            case CREATED -> io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_CREATED;
            case RUNNING -> io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_RUNNING;
            case WAITING -> io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_WAITING;
            case COMPLETED -> io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_COMPLETED;
            case FAILED -> io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_FAILED;
            case CANCELLED -> io.pipemesh.proto.v1.ExecutionStatus.EXECUTION_STATUS_CANCELLED;
        };
    }

    static StepOutcome toWire(StepRecord.StepOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> StepOutcome.STEP_OUTCOME_SUCCESS;
            case FAILED -> StepOutcome.STEP_OUTCOME_FAILED;
            case SUSPENDED -> StepOutcome.STEP_OUTCOME_SUSPENDED;
        };
    }

    static Timestamp toWire(long epochMillis) {
        return Timestamp.newBuilder()
                .setSeconds(epochMillis / 1000)
                .setNanos((int) (epochMillis % 1000) * 1_000_000)
                .build();
    }

    static io.pipemesh.proto.v1.ExecutionHandle toWire(ExecutionHandle handle) {
        return io.pipemesh.proto.v1.ExecutionHandle.newBuilder()
                .setExecutionId(handle.executionId().value())
                .setStatus(toWire(handle.status()))
                .setCurrentStepId(handle.currentStepIfAny().map(step -> step.value()).orElse(""))
                .build();
    }

    static io.pipemesh.proto.v1.ExecutionSnapshot toWire(ExecutionSnapshot snapshot) {
        return io.pipemesh.proto.v1.ExecutionSnapshot.newBuilder()
                .setExecutionId(snapshot.executionId().value())
                .setOrganizationId(snapshot.organization().value())
                .setWorkflowId(snapshot.workflowId().value())
                .setWorkflowVersion(snapshot.workflowVersion().value())
                .setStatus(toWire(snapshot.status()))
                .setCurrentStepId(snapshot.currentStepIfAny().map(step -> step.value()).orElse(""))
                .setVariables(JsonStructs.toStruct(snapshot.variables()))
                .setCreatedAt(toWire(snapshot.createdAtEpochMillis()))
                .setUpdatedAt(toWire(snapshot.updatedAtEpochMillis()))
                .build();
    }
}
