package io.pipemesh.runtime;

import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.grpc.UpdateChannel;
import io.pipemesh.proto.v1.ExecutionUpdate;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Carries updates between processes over PostgreSQL {@code LISTEN/NOTIFY}
 * (§30.1).
 *
 * <p>The database is already shared, so this needs nothing new to operate.
 * Redis, NATS or Kafka would each be a third thing to run for a job Postgres
 * does adequately at this size — and the interface stays here for the day that
 * stops being true.
 *
 * <p>Two properties are inherited from {@code NOTIFY} and worth stating rather
 * than discovering: it is not durable, so a process that is not listening misses
 * what happened (the in-memory broker has always behaved this way), and its
 * payload is limited, which is handled below.
 */
public final class PostgresUpdateChannel implements UpdateChannel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresUpdateChannel.class);

    private static final String CHANNEL = "pipemesh_execution";

    /**
     * PostgreSQL refuses a payload over 8000 bytes. Encoding costs a third, so
     * this leaves room rather than finding the wall at run time.
     */
    private static final int PAYLOAD_LIMIT = 5000;

    /** Sent instead of an update too large to carry: something happened, go look. */
    private static final String TOO_LARGE = "!";

    private static final String SEPARATOR = "|";

    /** Distinguishes this process's own notifications, which it already delivered. */
    private final String publisherId = UUID.randomUUID().toString();

    private final DataSource dataSource;
    private final ExecutorService listener = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public PostgresUpdateChannel(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    @Override
    public void publish(ExecutionId executionId, ExecutionUpdate update) {
        String payload = payloadOf(executionId, update);

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {

            statement.setString(1, CHANNEL);
            statement.setString(2, payload);
            statement.execute();
        } catch (Exception failure) {
            // An observer may never fail an execution (§22.1). A watcher on
            // another process misses this update; the execution carries on.
            log.warn("Could not publish an update for {}", executionId, failure);
        }
    }

    @Override
    public AutoCloseable subscribe(BiConsumer<ExecutionId, ExecutionUpdate> onUpdate) {
        listener.execute(() -> listen(onUpdate));
        return this::close;
    }

    /**
     * Listens until closed, reconnecting when the connection drops.
     *
     * <p>A dropped listener is the dangerous failure here: nothing errors, no
     * update arrives, and the symptom is identical to the bug this class exists
     * to fix. So a reconnect is loud rather than quiet.
     */
    private void listen(BiConsumer<ExecutionId, ExecutionUpdate> onUpdate) {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                log.info("Listening for execution updates from other processes");
                drain(connection, onUpdate);
            } catch (Exception dropped) {
                if (running.get()) {
                    log.warn("Update listener dropped; reconnecting. Watchers on this process"
                            + " saw nothing from elsewhere while it was down.", dropped);
                    pause();
                }
            }
        }
    }

    private void drain(Connection connection, BiConsumer<ExecutionId, ExecutionUpdate> onUpdate)
            throws Exception {

        PGConnection listening = connection.unwrap(PGConnection.class);
        while (running.get() && !connection.isClosed()) {
            PGNotification[] arrived = listening.getNotifications(1_000);
            if (arrived == null) {
                continue;
            }
            for (PGNotification notification : arrived) {
                accept(notification.getParameter(), onUpdate);
            }
        }
    }

    private void accept(String payload, BiConsumer<ExecutionId, ExecutionUpdate> onUpdate) {
        String[] parts = payload.split("\\" + SEPARATOR, 3);
        if (parts.length < 3 || publisherId.equals(parts[0])) {
            // Our own notification: these watchers were served directly, and
            // handing them the same update again would double every stream.
            return;
        }
        if (TOO_LARGE.equals(parts[2])) {
            log.debug("An update for {} was too large to carry", parts[1]);
            return;
        }

        try {
            onUpdate.accept(
                    ExecutionId.of(parts[1]),
                    ExecutionUpdate.parseFrom(Base64.getDecoder().decode(parts[2])));
        } catch (Exception unreadable) {
            log.warn("Ignoring an unreadable update for {}", parts[1], unreadable);
        }
    }

    private String payloadOf(ExecutionId executionId, ExecutionUpdate update) {
        String encoded = Base64.getEncoder().encodeToString(update.toByteArray());
        String body = encoded.length() > PAYLOAD_LIMIT ? TOO_LARGE : encoded;
        return publisherId + SEPARATOR + executionId.value() + SEPARATOR + body;
    }

    private void pause() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        listener.shutdownNow();
    }
}
