package kyle.poultry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared test configuration: a real PostgreSQL database in Docker plus test-only application secrets.
 * <p>
 * Tests import it with {@code @Import} so they run against the same database engine as production, with the
 * real Flyway migrations. Docker must be running. Also reused by the local-run helper
 * {@code TestPoultryManagementSystemApplication}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Username of the owner account created at startup, for tests that need to log in or find the owner row. */
    public static final String OWNER_USERNAME = "test-owner";

    /** Password of that owner account (a test value, never used outside tests). */
    public static final String OWNER_PASSWORD = "test-owner-password";

    /**
     * Starts the PostgreSQL container.
     * <p>
     * {@code @ServiceConnection} makes Spring Boot point the datasource at the container automatically, so no
     * JDBC URL has to be configured.
     *
     * @return a PostgreSQL 17 container
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }

    /**
     * Test-only secrets; production supplies these through the environment.
     *
     * @return a registrar that sets the JWT signing secret and the bootstrap owner's credentials
     */
    @Bean
    DynamicPropertyRegistrar testSecrets() {
        return registry -> {
            // A fixed signing secret so tests can issue and verify tokens.
            registry.add("app.security.jwt-secret", () -> "test-only-secret-test-only-secret-0123456789");
            // The bootstrap owner is created on first start, giving tests a user to attach movements to.
            registry.add("app.bootstrap.owner-username", () -> OWNER_USERNAME);
            registry.add("app.bootstrap.owner-password", () -> OWNER_PASSWORD);
        };
    }
}
