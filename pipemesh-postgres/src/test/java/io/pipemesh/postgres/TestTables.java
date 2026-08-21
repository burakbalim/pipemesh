package io.pipemesh.postgres;

import javax.sql.DataSource;

/** Empties the schema between tests. */
final class TestTables {

    /**
     * Every table, in one statement — {@code workflow_wait} references
     * {@code workflow_execution}, so truncating them separately is refused.
     */
    private static final String TRUNCATE_ALL =
            "TRUNCATE workflow_lease, workflow_wait, workflow_step_history,"
                    + " workflow_approval, workflow_execution";

    private TestTables() {
    }

    static void empty(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(TRUNCATE_ALL);
        } catch (Exception failure) {
            throw new IllegalStateException("could not empty the schema", failure);
        }
    }
}
