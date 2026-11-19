package kyle.poultry.shared;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now". Business dates are interpreted in the farm's zone.
 * <p>
 * Services inject the {@link Clock} bean instead of calling {@code now()} directly, so "today" always
 * follows the configured farm time zone and tests can substitute a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /**
     * Creates the application-wide clock.
     *
     * @param zone the farm's time-zone id from the {@code app.zone} property (e.g. {@code Asia/Manila})
     * @return a system clock fixed to that zone
     * @throws java.time.DateTimeException if the configured zone id is not a valid time zone (fails at startup)
     */
    @Bean
    Clock clock(@Value("${app.zone}") String zone) {
        // The zone decides what "today" means near midnight, which matters for business dates.
        return Clock.system(ZoneId.of(zone));
    }
}
