package kyle.poultry.shared;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The single source of "now". Business dates are interpreted in the farm's zone. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    Clock clock(@Value("${app.zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
