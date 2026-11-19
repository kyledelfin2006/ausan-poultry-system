package kyle.poultry;

import org.springframework.boot.SpringApplication;

/**
 * Developer helper: runs the real application locally with a throw-away PostgreSQL Testcontainers database.
 * <p>
 * It is not a test and is not run by Maven; start its {@code main} from the IDE to try the application
 * without installing or configuring a database. Docker must be running.
 */
public class TestPoultryManagementSystemApplication {

    /**
     * Starts the application with {@link TestcontainersConfiguration} added, which supplies the database
     * container and the test-only secrets.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        // "from(...).with(...)" runs the normal main method but layers the test configuration on top of it.
        SpringApplication.from(PoultryManagementSystemApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
