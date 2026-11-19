package kyle.poultry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Ausan Poultry Farm backend, a modular monolith.
 * <p>
 * {@code @SpringBootApplication} turns on component scanning from this package, so every module
 * ({@code shared}, {@code identity}, {@code catalog}, {@code stock}, {@code finance}, {@code delivery}, ...)
 * is discovered automatically. Nothing else should be placed here.
 */
@SpringBootApplication
public class PoultryManagementSystemApplication {

    /**
     * Starts the embedded web server and the Spring application context.
     *
     * @param args command-line arguments, passed straight to Spring Boot (e.g. {@code --server.port=9000})
     */
    public static void main(String[] args) {
        // Boots the context: runs Flyway migrations, wires all modules, then starts listening for HTTP.
        SpringApplication.run(PoultryManagementSystemApplication.class, args);
    }

}
