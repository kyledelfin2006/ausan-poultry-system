package kyle.poultry.identity;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import kyle.poultry.shared.web.RequestIdFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security setup for the whole application: a stateless JWT resource server where every endpoint except login
 * and health requires a valid token (operational endpoints under {@code /actuator} need the OWNER role), CORS for the
 * configured frontend origins, security response headers, plus the token signing/verification beans and the password
 * encoder.
 * Package-private; other modules rely on it only through the authenticated {@code Jwt} and {@link Actor}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, BootstrapProperties.class, CorsProperties.class})
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
                .cors(c -> {})                              // uses the CorsConfigurationSource bean below
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        // An API returns data, never pages: forbid loading anything, framing, and sniffing.
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // Only sent on HTTPS requests (browsers ignore it on HTTP); with a TLS-terminating proxy the
                        // proxy must forward the scheme, see docs/security/README.md.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/actuator/health/**").permitAll()
                        // Metrics and other operational endpoints can reveal internals, so only the owner may read them.
                        .requestMatchers("/actuator/**").hasRole("OWNER")
                        // Wholly owner-only areas are also refused here, before the body is read, so an assistant
                        // gets 403 (not 400) for any request; @PreAuthorize stays as defence in depth.
                        .requestMatchers("/api/users", "/api/users/**", "/api/audit", "/api/audit/**").hasRole("OWNER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                // Missing, invalid or revoked credentials all answer a plain 401 with no details.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /**
     * Cross-origin rules for browsers: only the configured origins may call the API, with the headers a frontend needs.
     * No credentials mode is enabled because the API uses a bearer header, not cookies.
     *
     * @param props the allowed origins and preflight cache time
     * @return the CORS source applied to every path
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        var cors = new CorsConfiguration();
        // Empty list = nobody is allowed cross-origin; the browser then blocks the call, which is the safe default.
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key", RequestIdFilter.HEADER));
        // Headers a frontend may read from responses (Retry-After on 429, Content-Disposition for downloads).
        cors.setExposedHeaders(List.of(RequestIdFilter.HEADER, HttpHeaders.RETRY_AFTER, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.LOCATION));
        cors.setAllowCredentials(false);
        cors.setMaxAge(props.maxAge());
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
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
