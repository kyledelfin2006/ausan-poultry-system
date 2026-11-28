package kyle.poultry.identity.internal.security;

import kyle.poultry.identity.api.model.Capability;
import kyle.poultry.identity.internal.config.BootstrapProperties;
import kyle.poultry.identity.internal.config.CorsProperties;
import kyle.poultry.identity.internal.config.SecurityProperties;
import kyle.poultry.identity.internal.persistence.AppUserRepository;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
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
 * Internal to the identity module; other modules rely on it only through the authenticated {@code Jwt} and {@code Actor}.
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
                        // proxy must forward the scheme, see docs/security.md.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/actuator/health/**").permitAll()
                        // Metrics and other operational endpoints can reveal internals, so only the owner may read them.
                        .requestMatchers("/actuator/**").access(ownerWithoutPasswordChange())
                        // Wholly owner-only areas are also refused here, before the body is read, so an assistant
                        // gets 403 (not 400) for any request; @PreAuthorize stays as defence in depth.
                        .requestMatchers("/api/users", "/api/users/**").access(requiring(Capability.MANAGE_USERS))
                        .requestMatchers("/api/audit", "/api/audit/**").access(requiring(Capability.VIEW_AUDIT_LOG))
                        .anyRequest().access(authenticatedUnlessPasswordChangeRequired()))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                // Missing, invalid or revoked credentials all answer a plain 401 with no details.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** Keeps users with a temporary password to the small set of routes needed to finish or abandon sign-in. */
    private static AuthorizationManager<RequestAuthorizationContext> authenticatedUnlessPasswordChangeRequired() {
        return (authentication, context) -> {
            Authentication current = authentication.get();
            if (!isAuthenticated(current)) return new AuthorizationDecision(false);
            if (!hasAuthority(current, RevocationAuthenticationConverter.MUST_CHANGE_PASSWORD))
                return new AuthorizationDecision(true);
            var request = context.getRequest();
            boolean allowed = ("GET".equals(request.getMethod()) && "/api/auth/me".equals(request.getServletPath()))
                    || ("POST".equals(request.getMethod())
                    && ("/api/auth/password".equals(request.getServletPath())
                    || "/api/auth/logout".equals(request.getServletPath())));
            return new AuthorizationDecision(allowed);
        };
    }

    /** Applies the same temporary-password restriction before the operational (actuator) owner rule. */
    private static AuthorizationManager<RequestAuthorizationContext> ownerWithoutPasswordChange() {
        return authorityWithoutPasswordChange("ROLE_OWNER");
    }

    /** Applies the same temporary-password restriction before a capability URL rule. */
    private static AuthorizationManager<RequestAuthorizationContext> requiring(Capability capability) {
        return authorityWithoutPasswordChange(capability.authority());
    }

    private static AuthorizationManager<RequestAuthorizationContext> authorityWithoutPasswordChange(String authority) {
        return (authentication, context) -> {
            Authentication current = authentication.get();
            return new AuthorizationDecision(isAuthenticated(current)
                    && !hasAuthority(current, RevocationAuthenticationConverter.MUST_CHANGE_PASSWORD)
                    && hasAuthority(current, authority));
        };
    }

    /** Lets {@code RequiresCapability} fill its {@code {value}} placeholder into the {@code @PreAuthorize} expression. */
    @Bean
    static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }

    /** Excludes anonymous requests even though the framework marks their token as authenticated. */
    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** Checks whether the current identity has one of the authorities supplied by the converter. */
    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
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
