package io.pipemesh.postgres;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the schema this store needs, in order, once each.
 *
 * <p>Small on purpose. A migration tool is a reasonable thing for an application
 * to choose; it is not a reasonable thing for a library to impose, and every
 * dependency a runtime carries is one its embedder inherits.
 */
public final class SchemaMigrator {

    private static final String LOCATION = "/io/pipemesh/postgres/migration/";

    private static final List<String> MIGRATIONS = List.of("V001__execution_state.sql", "V002__execution_lease.sql");

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source");
    }

    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            createHistoryTable(connection);
            List<String> applied = appliedMigrations(connection);
            for (String migration : MIGRATIONS) {
                if (!applied.contains(migration)) {
                    apply(connection, migration);
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new StateStoreException("schema migration failed", failure);
        }
    }

    private void createHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pipemesh_schema_history (
                        migration  TEXT PRIMARY KEY,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
    }

    private List<String> appliedMigrations(Connection connection) throws SQLException {
        List<String> applied = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT migration FROM pipemesh_schema_history")) {
            while (rows.next()) {
                applied.add(rows.getString(1));
            }
        }
        return applied;
    }

    private void apply(Connection connection, String migration) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(read(migration));
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pipemesh_schema_history (migration) VALUES (?)")) {
            statement.setString(1, migration);
            statement.executeUpdate();
        }
    }

    private String read(String migration) {
        try (InputStream sql = SchemaMigrator.class.getResourceAsStream(LOCATION + migration)) {
            if (sql == null) {
                throw new StateStoreException("migration not found: " + migration, null);
            }
            return new String(sql.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
