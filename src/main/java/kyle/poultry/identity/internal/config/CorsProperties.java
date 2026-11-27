package kyle.poultry.identity.internal.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings {@code app.cors.*}: which browser origins (the address a web frontend is served from) may call the API.
 * Internal to the identity module; used by {@code SecurityConfig}. An empty list means no cross-origin browser access at all, which is
 * the right value when the frontend is served from the same origin as the API (for example behind one reverse proxy).
 *
 * <p>Origins must be exact ({@code scheme://host[:port]}, no path, no trailing slash). Wildcards are refused on
 * purpose: a "*" would let any website a user visits call this API from their browser.
 *
 * @param allowedOrigins exact origins allowed to call the API, such as {@code http://localhost:5173}
 * @param maxAge         how long a browser may cache the answer to a preflight (OPTIONS) request; default 1 hour
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins, Duration maxAge) {
    /** An origin is scheme, host and optional port only. */
    private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:\\d{1,5})?");

    /**
     * Validates the origins and applies defaults.
     *
     * @throws IllegalStateException when an origin contains a wildcard or is not a plain {@code scheme://host[:port]}
     */
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
        for (String origin : allowedOrigins) {
            // Fail at startup rather than silently run with a rule that does not do what its author thinks.
            if (origin.contains("*")) throw new IllegalStateException("app.cors.allowed-origins must not contain wildcards: " + origin);
            if (!ORIGIN.matcher(origin).matches() || URI.create(origin).getHost() == null)
                throw new IllegalStateException("app.cors.allowed-origins entries must look like https://host[:port]: " + origin);
        }
        if (maxAge == null) maxAge = Duration.ofHours(1);
    }
}
