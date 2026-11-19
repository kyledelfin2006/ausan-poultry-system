package kyle.poultry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String OWNER_USERNAME = "test-owner";
    public static final String OWNER_PASSWORD = "test-owner-password";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }

    /** Test-only secrets; production supplies these through the environment. */
    @Bean
    DynamicPropertyRegistrar testSecrets() {
        return registry -> {
            registry.add("app.security.jwt-secret", () -> "test-only-secret-test-only-secret-0123456789");
            registry.add("app.bootstrap.owner-username", () -> OWNER_USERNAME);
            registry.add("app.bootstrap.owner-password", () -> OWNER_PASSWORD);
        };
    }
}
