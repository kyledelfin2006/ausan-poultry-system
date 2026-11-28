package kyle.poultry.identity.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Signing-key configuration rejects short or low-diversity values and never prints key material. */
class SecurityPropertiesTests {
    private static final String DIVERSE_KEY = "0123456789abcdefghijklmnopqrstuv";

    /** A signing key must contain at least 32 UTF-8 bytes. */
    @Test
    void rejectsAKeyShorterThanThirtyTwoBytes() {
        assertThatThrownBy(() -> new SecurityProperties("x".repeat(31), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    /** A long repeated pattern still has too little diversity to be a safe signing key. */
    @Test
    void rejectsALowDiversityKey() {
        assertThatThrownBy(() -> new SecurityProperties("abcde".repeat(8), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 distinct byte values");
    }

    /** Diverse keys are accepted, and logging their settings cannot disclose the key. */
    @Test
    void acceptsADiverseKeyAndRedactsItFromToString() {
        var properties = new SecurityProperties(DIVERSE_KEY, null);

        assertThat(properties.jwtSecret()).isEqualTo(DIVERSE_KEY);
        assertThat(properties.toString()).contains("<redacted>").doesNotContain(DIVERSE_KEY);
    }
}
