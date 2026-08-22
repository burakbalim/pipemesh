package io.pipemesh.console;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The base every console test builds on: a real PostgreSQL, the real schema and
 * the real HTTP layer.
 *
 * <p>The database is real for the same reason the runtime's tests use one — the
 * rules being tested here are enforced by constraints and by single statements
 * that check and write together, and neither survives being mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ConsoleTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("console.cookie.secure", () -> false);
    }

    @Autowired
    protected MockMvc http;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Accounts only. The plan rows are schema, not test data. */
    @AfterEach
    void emptyAccounts() {
        jdbc.execute("TRUNCATE console_payment_event, console_subscription, console_api_key,"
                + " console_session, console_verification, console_user,"
                + " console_organization CASCADE");
        // Executions are the usage ledger (§39.1), so they are test data too.
        jdbc.execute("TRUNCATE workflow_execution CASCADE");
    }
}
