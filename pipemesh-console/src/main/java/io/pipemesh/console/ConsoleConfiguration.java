package io.pipemesh.console;

import io.pipemesh.postgres.SchemaMigrator;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/** What the console needs beyond what Spring Boot works out for itself. */
@Configuration
public class ConsoleConfiguration {

    private static final String MIGRATIONS = "/io/pipemesh/console/migration/";

    /**
     * A bean rather than {@code Instant.now()} scattered through the services:
     * expiry, verification windows and session lifetime are all time arithmetic,
     * and none of it is testable against a clock nobody can move.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Applies both schemas, runtime first.
     *
     * <p>The console is the deployment, so it owns getting the database into
     * shape — and it reads the runtime's tables: usage comes off
     * {@code workflow_execution} rather than being counted a second time (§39.1).
     * Migrating only half would leave a console that starts and then cannot
     * answer what anybody has used.
     *
     * <p>One migrator and one history table, so "what has been applied" has one
     * answer. The dependency still runs one way: the console knows about the
     * runtime's schema, and the runtime knows nothing about the console's.
     */
    @Bean
    public InitializingBean consoleSchema(DataSource dataSource) {
        return () -> {
            new SchemaMigrator(dataSource).migrate();
            new SchemaMigrator(dataSource, MIGRATIONS, List.of(
                    "V101__console_identity.sql",
                    "V102__console_api_key.sql",
                    "V104__console_plans.sql",
                    "V105__console_subscription.sql")).migrate();
        };
    }
}
