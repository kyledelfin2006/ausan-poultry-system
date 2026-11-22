package kyle.poultry.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings {@code app.security.*}: the JWT signing secret and token lifetime. Package-private; used by
 * {@link SecurityConfig} and {@link AuthService}. Fails startup if the secret is too short.
 *
 * @param jwtSecret the HS256 signing secret, at least 32 bytes, supplied through the environment
 * @param tokenTtl  how long a token stays valid; defaults to 8 hours
 */
@ConfigurationProperties("app.security")
record SecurityProperties(String jwtSecret, Duration tokenTtl) {

    /**
     * Validates the settings and applies the default lifetime.
     *
     * @throws IllegalStateException when the secret is missing or shorter than 32 bytes (HS256 needs 256 bits)
     */
    SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("app.security.jwt-secret (APP_SECURITY_JWT_SECRET) must be at least 32 bytes");
        if (tokenTtl == null) tokenTtl = Duration.ofHours(8);
    }

    /** {@return a description that never includes the signing secret, so logging this object cannot leak it} */
    @Override
    public String toString() { return "SecurityProperties[jwtSecret=<redacted>, tokenTtl=" + tokenTtl + "]"; }
}
