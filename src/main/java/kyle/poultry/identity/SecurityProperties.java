package kyle.poultry.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
record SecurityProperties(String jwtSecret, Duration tokenTtl) {
    SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("app.security.jwt-secret (APP_SECURITY_JWT_SECRET) must be at least 32 bytes");
        if (tokenTtl == null) tokenTtl = Duration.ofHours(8);
    }
}
