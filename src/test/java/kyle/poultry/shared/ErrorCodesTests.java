package kyle.poultry.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatusCode;

/** Unit tests for the error-code catalogue: the status mapping and the consistency of the published list. */
class ErrorCodesTests {

    /**
     * Each documented status maps to its generic code.
     *
     * @param status the HTTP status value
     * @param code   the expected code
     */
    @ParameterizedTest
    @CsvSource({"400,BAD_REQUEST", "401,UNAUTHENTICATED", "403,FORBIDDEN", "404,NOT_FOUND", "405,METHOD_NOT_ALLOWED",
            "406,NOT_ACCEPTABLE", "409,CONFLICT", "415,UNSUPPORTED_MEDIA_TYPE", "422,RULE_VIOLATION",
            "429,TOO_MANY_REQUESTS", "500,INTERNAL_ERROR"})
    void mapsEachDocumentedStatus(int status, String code) {
        assertThat(ErrorCodes.forStatus(HttpStatusCode.valueOf(status))).isEqualTo(code);
    }

    /** A status without a dedicated code still gets one: other 4xx are bad requests, every 5xx is internal. */
    @Test
    void unknownStatusesFallBackInsteadOfFailing() {
        assertThat(ErrorCodes.forStatus(HttpStatusCode.valueOf(418))).isEqualTo(ErrorCodes.BAD_REQUEST);
        assertThat(ErrorCodes.forStatus(HttpStatusCode.valueOf(503))).isEqualTo(ErrorCodes.INTERNAL_ERROR);
        assertThat(ErrorCodes.forStatus(HttpStatusCode.valueOf(599))).isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }

    /** Every code constant is in {@code ALL} exactly once, so adding a constant cannot skip the published list. */
    @Test
    void allListsEveryConstantOnce() {
        var constants = Arrays.stream(ErrorCodes.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && f.getType() == String.class)
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
        assertThat(ErrorCodes.ALL).containsExactlyInAnyOrderElementsOf(constants);
        assertThat(new HashSet<>(ErrorCodes.ALL)).hasSameSizeAs(ErrorCodes.ALL);
    }
}
