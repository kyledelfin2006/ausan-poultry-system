package kyle.poultry.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure unit tests of {@link CorsProperties} validation: no Spring, no database. */
class CorsPropertiesTests {

    /** Exact origins are accepted; blanks and surrounding spaces are dropped; the default preflight cache is 1 hour. */
    @Test
    void acceptsExactOriginsAndAppliesDefaults() {
        var p = new CorsProperties(List.of(" https://app.example.ph ", "http://localhost:5173", ""), null);
        assertThat(p.allowedOrigins()).containsExactly("https://app.example.ph", "http://localhost:5173");
        assertThat(p.maxAge()).isEqualTo(Duration.ofHours(1));
    }

    /** No configuration at all means no origin is allowed. */
    @Test
    void nullOrEmptyMeansNobodyIsAllowed() {
        assertThat(new CorsProperties(null, null).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(Arrays.asList("", "  "), null).allowedOrigins()).isEmpty();
    }

    /** Wildcards and anything that is not a bare scheme://host[:port] stop the application from starting. */
    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.ph", "http://*", "https://app.example.ph/", "https://app.example.ph/path",
            "app.example.ph", "ftp://app.example.ph", "https://app.example.ph:99999x", "javascript:alert(1)"})
    void refusesWildcardsAndMalformedOrigins(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of(origin), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.cors.allowed-origins");
    }
}
