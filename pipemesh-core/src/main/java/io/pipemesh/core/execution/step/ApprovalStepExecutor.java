package io.pipemesh.core.execution.step;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.ResumableStepExecutor;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.execution.SuspensionReason;
import io.pipemesh.core.state.ApprovalRecord;
import io.pipemesh.core.state.ApprovalStore;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stops an execution until a person decides (§9.4).
 *
 * <p>The wait costs nothing while it lasts: the step returns
 * {@link StepResult.Suspend}, the engine persists and lets go, and no thread is
 * held for however long the decision takes (§16).
 *
 * <p>The approval id is derived from the execution and the step rather than
 * generated, so re-entering this step produces the same id. That is what makes a
 * repeated decision land on the same row instead of creating a second one.
 */
public final class ApprovalStepExecutor implements ResumableStepExecutor {

    private static final JsonNode SCHEMA = StepSchemas.parse("""
            {
              "properties": {
                "message":        {"type": "string"},
                "onApproved":     {"type": "string"},
                "onRejected":     {"type": "string"},
                "timeoutSeconds": {"type": "integer"}
              },
              "required": ["onApproved", "onRejected"]
            }
            """);

    private static final String MESSAGE = "message";
    private static final String ON_APPROVED = "onApproved";
    private static final String ON_REJECTED = "onRejected";
    private static final String TIMEOUT_SECONDS = "timeoutSeconds";

    private final ApprovalStore approvals;
    private final Clock clock;

    public ApprovalStepExecutor(ApprovalStore approvals) {
        this(approvals, Clock.systemUTC());
    }

    public ApprovalStepExecutor(ApprovalStore approvals, Clock clock) {
        this.approvals = Objects.requireNonNull(approvals, "approval store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The id a caller must quote to resume this step. */
    public static String approvalId(ExecutionContext context, Step step) {
        return context.executionId().value() + ":" + step.id().value();
    }

    @Override
    public boolean supports(StepType type) {
        return StepType.HUMAN_APPROVAL.equals(type);
    }


    /** What a human_approval step may say. Anything else is refused at load time (§23.1). */
    @Override
    public Optional<JsonNode> configSchema() {
        return Optional.of(SCHEMA);
    }

    @Override
    public StepResult execute(Step step, ExecutionContext context) {
        String approvalId = approvalId(context, step);
        long now = clock.millis();

        approvals.create(new ApprovalRecord(
                approvalId,
                context.executionId(),
                step.id(),
                step.config().path(MESSAGE).asText(""),
                ApprovalRecord.ApprovalStatus.PENDING,
                "",
                "",
                now,
                0L,
                expiryOf(step, now)));

        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("approvalId", approvalId)
                .put(MESSAGE, step.config().path(MESSAGE).asText(""));

        return new StepResult.Suspend(
                new SuspensionReason(SuspensionReason.APPROVAL, detail), timeoutOf(step));
    }

    @Override
    public StepResult resume(Step step, ExecutionContext context, ResumeSignal signal) {
        if (!(signal instanceof ResumeSignal.Approval decision)) {
            return new StepResult.Failed("approval.unexpected_signal",
                    "step " + step.id() + " cannot be resumed by " + signal.getClass().getSimpleName(),
                    false);
        }
        String expected = approvalId(context, step);
        if (!expected.equals(decision.signalId())) {
            return new StepResult.Failed("approval.unknown_signal",
                    "expected approval '" + expected + "', got '" + decision.signalId() + "'", false);
        }
        Optional<ApprovalRecord> settled = settle(expected, decision);
        if (settled.isEmpty()) {
            return new StepResult.Failed("approval.already_settled",
                    "approval '" + expected + "' was already decided", false);
        }
        return StepResult.Continue.to(branch(step, decision.approved()));
    }

    @Override
    public List<StepId> outgoing(Step step) {
        return Stepwiring.stepIds(step, ON_APPROVED, ON_REJECTED);
    }

    private Optional<ApprovalRecord> settle(String approvalId, ResumeSignal.Approval decision) {
        Optional<ApprovalRecord> pending = approvals.find(approvalId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        ApprovalRecord current = pending.get();
        return approvals.settle(new ApprovalRecord(
                current.approvalId(),
                current.executionId(),
                current.stepId(),
                current.message(),
                decision.approved()
                        ? ApprovalRecord.ApprovalStatus.APPROVED
                        : ApprovalRecord.ApprovalStatus.REJECTED,
                decision.decidedBy(),
                decision.comment(),
                current.requestedAtEpochMillis(),
                clock.millis(),
                current.expiresAtEpochMillis()));
    }

    private StepId branch(Step step, boolean approved) {
        String field = approved ? ON_APPROVED : ON_REJECTED;
        String target = step.config().path(field).asText("");
        if (target.isBlank()) {
            throw new IllegalStateException("step " + step.id() + " has no '" + field + "' target");
        }
        return StepId.of(target);
    }

    private Duration timeoutOf(Step step) {
        long seconds = step.config().path(TIMEOUT_SECONDS).asLong(0L);
        return seconds > 0 ? Duration.ofSeconds(seconds) : null;
    }

    private long expiryOf(Step step, long now) {
        Duration timeout = timeoutOf(step);
        return timeout == null ? 0L : now + timeout.toMillis();
    }
}
