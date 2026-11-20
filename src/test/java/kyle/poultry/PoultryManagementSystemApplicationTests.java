package kyle.poultry;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Smoke test: the whole application starts against a real PostgreSQL container.
 * <p>
 * Starting the context runs every Flyway migration and wires all modules, so a broken migration, a missing
 * bean or a bad configuration property fails here before any more specific test runs.
 */
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@SpringBootTest
class PoultryManagementSystemApplicationTests {

    /** Proves the full application context can start: migrations apply and all beans wire together. */
    @Test
    void contextLoads() {
        // Arrange/act/assert are all implicit: the test passes if the context starts without an exception.
    }

}
