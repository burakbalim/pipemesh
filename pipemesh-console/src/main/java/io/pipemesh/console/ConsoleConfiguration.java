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
     * Applies the console's own tables, reusing the runtime's migrator so both
     * schemas report into one history table (§26.2 — the console depends on the
     * runtime, never the other way round).
     */
    @Bean
    public InitializingBean consoleSchema(DataSource dataSource) {
        return () -> new SchemaMigrator(
                dataSource, MIGRATIONS, List.of("V101__console_identity.sql")).migrate();
    }
}
