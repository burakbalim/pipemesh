package io.pipemesh.postgres;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StaleExecutionException;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowVersion;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Execution state on PostgreSQL — the store the durability claim actually rests on.
 *
 * <p>{@link #advance} writes the step history entry and the new execution state
 * inside one transaction, and updates the row only if its version is still the
 * one that was read. A crash between the two writes is therefore impossible, and
 * a second worker acting on stale state is rejected rather than accommodated.
 *
 * <p>Nothing here calls a model or a tool. Provider I/O happens before this class
 * is reached, which is what keeps a slow API from holding a database transaction
 * open.
 */
public final class PostgresStateStore implements StateStore {

    private final DataSource dataSource;

    public PostgresStateStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    @Override
    public ExecutionRecord create(ExecutionRecord record) {
        String sql = """
                INSERT INTO workflow_execution
                    (execution_id, organization_id, workflow_id, workflow_version, status,
                     current_step, variables, trace_context, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, record.executionId().value());
            statement.setString(2, record.organization().value());
            statement.setString(3, record.workflowId().value());
            statement.setString(4, record.workflowVersion().value());
            statement.setString(5, record.status().name());
            statement.setString(6, stepValue(record.currentStep()));
            statement.setObject(7, JsonColumn.toJsonb(record.variables()));
            statement.setString(8, record.traceContext());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new StateStoreException("could not create execution " + record.executionId(), failure);
        }
        return find(record.executionId()).orElseThrow(
                () -> new StateStoreException("execution vanished after insert", null));
    }

    @Override
    public Optional<ExecutionRecord> find(ExecutionId executionId) {
        String sql = """
                SELECT execution_id, organization_id, workflow_id, workflow_version, status,
                       current_step, variables, trace_context, version, created_at, updated_at
                  FROM workflow_execution
                 WHERE execution_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, executionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StateStoreException("could not read execution " + executionId, failure);
        }
    }

    @Override
    public ExecutionRecord advance(ExecutionRecord record, StepRecord step) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!updateExecution(connection, record)) {
                    connection.rollback();
                    throw new StaleExecutionException(record.executionId(), record.version());
                }
                insertStep(connection, step);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new StateStoreException("could not advance execution " + record.executionId(), failure);
        }
        return find(record.executionId()).orElseThrow(
                () -> new StateStoreException("execution vanished after update", null));
    }

    @Override
    public List<ExecutionRecord> findStale(ExecutionStatus status, long untouchedSince, int limit) {
        String sql = """
                SELECT execution_id, organization_id, workflow_id, workflow_version, status,
                       current_step, variables, trace_context, version, created_at, updated_at
                  FROM workflow_execution
                 WHERE status = ? AND updated_at < ?
                 ORDER BY updated_at
                 LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, status.name());
            statement.setTimestamp(2, new Timestamp(untouchedSince));
            statement.setInt(3, Math.max(0, limit));

            List<ExecutionRecord> stale = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stale.add(read(rows));
                }
            }
            return List.copyOf(stale);
        } catch (SQLException failure) {
            throw new StateStoreException("could not scan for stale executions", failure);
        }
    }

    public List<StepRecord> historyOf(ExecutionId executionId) {
        String sql = """
                SELECT execution_id, step_id, step_type, outcome, input_snapshot, output_snapshot,
                       model_id, prompt_version, input_tokens, output_tokens, latency_ms,
                       started_at, finished_at, attributes, attempt
                  FROM workflow_step_history
                 WHERE execution_id = ?
                 ORDER BY id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, executionId.value());
            List<StepRecord> history = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    history.add(readStep(rows));
                }
            }
            return List.copyOf(history);
        } catch (SQLException failure) {
            throw new StateStoreException("could not read history of " + executionId, failure);
        }
    }

    private boolean updateExecution(Connection connection, ExecutionRecord record) throws SQLException {
        String sql = """
                UPDATE workflow_execution
                   SET status = ?, current_step = ?, variables = ?, trace_context = ?,
                       version = version + 1, updated_at = now()
                 WHERE execution_id = ? AND version = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.status().name());
            statement.setString(2, stepValue(record.currentStep()));
            statement.setObject(3, JsonColumn.toJsonb(record.variables()));
            statement.setString(4, record.traceContext());
            statement.setString(5, record.executionId().value());
            statement.setLong(6, record.version());
            return statement.executeUpdate() == 1;
        }
    }

    private void insertStep(Connection connection, StepRecord step) throws SQLException {
        String sql = """
                INSERT INTO workflow_step_history
                    (execution_id, step_id, step_type, outcome, input_snapshot, output_snapshot,
                     model_id, prompt_version, input_tokens, output_tokens, latency_ms,
                     started_at, finished_at, attributes, attempt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, step.executionId().value());
            statement.setString(2, step.stepId().value());
            statement.setString(3, step.stepType().name());
            statement.setString(4, step.outcome().name());
            statement.setObject(5, JsonColumn.toJsonb(step.input()));
            statement.setObject(6, JsonColumn.toJsonb(step.output()));
            statement.setString(7, step.modelId());
            statement.setString(8, step.promptVersion());
            statement.setLong(9, step.inputTokens());
            statement.setLong(10, step.outputTokens());
            statement.setLong(11, step.latencyMillis());
            statement.setTimestamp(12, new Timestamp(step.startedAtEpochMillis()));
            statement.setTimestamp(13, new Timestamp(step.finishedAtEpochMillis()));
            statement.setObject(14, JsonColumn.toJsonb(step.attributes()));
            statement.setInt(15, step.attempt());
            statement.executeUpdate();
        }
    }

    private ExecutionRecord read(ResultSet rows) throws SQLException {
        String currentStep = rows.getString("current_step");
        return new ExecutionRecord(
                ExecutionId.of(rows.getString("execution_id")),
                OrganizationId.of(rows.getString("organization_id")),
                WorkflowId.of(rows.getString("workflow_id")),
                WorkflowVersion.of(rows.getString("workflow_version")),
                ExecutionStatus.valueOf(rows.getString("status")),
                currentStep == null ? null : StepId.of(currentStep),
                JsonColumn.readObject(rows.getString("variables")),
                rows.getString("trace_context"),
                rows.getLong("version"),
                rows.getTimestamp("created_at").getTime(),
                rows.getTimestamp("updated_at").getTime());
    }

    private StepRecord readStep(ResultSet rows) throws SQLException {
        return new StepRecord(
                ExecutionId.of(rows.getString("execution_id")),
                StepId.of(rows.getString("step_id")),
                StepType.of(rows.getString("step_type")),
                StepRecord.StepOutcome.valueOf(rows.getString("outcome")),
                JsonColumn.readObject(rows.getString("input_snapshot")),
                JsonColumn.readObject(rows.getString("output_snapshot")),
                rows.getString("model_id"),
                rows.getString("prompt_version"),
                rows.getLong("input_tokens"),
                rows.getLong("output_tokens"),
                rows.getLong("latency_ms"),
                rows.getTimestamp("started_at").getTime(),
                rows.getTimestamp("finished_at").getTime(),
                JsonColumn.readObject(rows.getString("attributes")),
                rows.getInt("attempt"));
    }

    private String stepValue(StepId stepId) {
        return stepId == null ? null : stepId.value();
    }
}
