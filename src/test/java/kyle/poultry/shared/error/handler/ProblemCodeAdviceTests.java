package kyle.poultry.shared.error.handler;

import kyle.poultry.shared.error.ErrorCodes;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Unit tests of the advice that guarantees every problem detail carries a {@code code}. */
class ProblemCodeAdviceTests {

    private final ProblemCodeAdvice advice = new ProblemCodeAdvice();

    /**
     * Runs the advice the way Spring would just before writing a body.
     *
     * @param body the value about to be serialised
     * @return what the advice hands back
     */
    private Object write(Object body) {
        return advice.beforeBodyWrite(body, null, null, null, null, null);
    }

    /** It applies to every return type, because the body type is only known at write time. */
    @Test
    void appliesToEveryResponse() {
        assertThat(advice.supports(null, null)).isTrue();
    }

    /** A problem built elsewhere (the login throttle's 429) gets the generic code for its status. */
    @Test
    void fillsAMissingCodeFromTheStatus() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "slow down");
        assertThat(write(problem)).isSameAs(problem);
        assertThat(problem.getProperties()).containsEntry("code", ErrorCodes.TOO_MANY_REQUESTS);
    }

    /** A specific code chosen by a handler is never replaced by the generic one. */
    @Test
    void keepsAnExistingCode() {
        var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setProperty("code", ErrorCodes.DATA_CONFLICT);
        write(problem);
        assertThat(problem.getProperties()).containsEntry("code", ErrorCodes.DATA_CONFLICT);
    }

    /** Bodies that are not problem details pass through untouched. */
    @Test
    void ignoresOtherBodies() {
        var body = Map.of("a", 1);
        assertThat(write(body)).isSameAs(body);
        assertThat(write(null)).isNull();
    }
}
