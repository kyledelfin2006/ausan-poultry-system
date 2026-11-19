package kyle.poultry.identity;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Spring Security setup for the whole application: a stateless JWT resource server where every endpoint except login
 * and health requires a valid token, plus the token signing/verification beans and the password encoder.
 * Package-private; other modules rely on it only through the authenticated {@code Jwt} and {@link Actor}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, BootstrapProperties.class})
class SecurityConfig {

    /**
     * Defines which requests need authentication and how tokens are turned into an authenticated user.
     *
     * @param http      the Spring Security builder
     * @param converter converts a verified token into an authentication, re-checking the user in the database
     * @return the filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, RevocationAuthenticationConverter converter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())               // stateless bearer-token API: no cookies, no CSRF surface
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                // Missing, invalid or revoked credentials all answer a plain 401 with no details.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /**
     * The converter that runs after signature and expiry checks and enforces immediate revocation.
     *
     * @param users user lookup used on every request
     * @return the converter
     */
    @Bean
    RevocationAuthenticationConverter jwtAuthenticationConverter(AppUserRepository users) {
        return new RevocationAuthenticationConverter(users);
    }

    /**
     * Builds the HS256 signing key from the configured secret.
     *
     * @param props security settings holding the secret
     * @return the secret key
     */
    @Bean
    SecretKey jwtKey(SecurityProperties props) {
        return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Signs tokens at login.
     *
     * @param jwtKey the shared secret key
     * @return the encoder
     */
    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    /**
     * Verifies token signatures and expiry on every request; pinned to HS256 so no other algorithm is accepted.
     *
     * @param jwtKey the shared secret key
     * @return the decoder
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Password hashing for all stored passwords.
     *
     * @return a BCrypt encoder (salted, adaptive cost)
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
