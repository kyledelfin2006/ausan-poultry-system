package kyle.poultry.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Unit tests of the exception-to-problem mapping in isolation (no Spring context): status, code and detail of each
 * handler, that nothing internal leaks, and that security exceptions are handed back untouched.
 */
class ApiExceptionHandlerTests {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /**
     * Reads the {@code code} extension of a problem.
     *
     * @param problem the problem detail
     * @return its code
     */
    private static Object code(ProblemDetail problem) {
        return problem.getProperties().get("code");
    }

    /** A missing record is 404 with the exception's message. */
    @Test
    void notFoundIs404() {
        var problem = handler.notFound(new NotFoundException("Delivery", 99));
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(code(problem)).isEqualTo(ErrorCodes.NOT_FOUND);
        assertThat(problem.getDetail()).isEqualTo("Delivery 99 not found");
    }

    /** A broken business rule is 422 with the exception's message. */
    @Test
    void ruleViolationIs422() {
        var problem = handler.rule(new RuleViolation("Date cannot be in the future"));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(code(problem)).isEqualTo(ErrorCodes.RULE_VIOLATION);
        assertThat(problem.getDetail()).isEqualTo("Date cannot be in the future");
    }

    /** A state conflict is 409 {@code CONFLICT} with the exception's message. */
    @Test
    void conflictIs409() {
        var problem = handler.conflict(new ConflictException("Username already taken"));
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(code(problem)).isEqualTo(ErrorCodes.CONFLICT);
        assertThat(problem.getDetail()).isEqualTo("Username already taken");
    }

    /** A database constraint failure is 409 {@code DATA_CONFLICT} and never repeats the SQL text. */
    @Test
    void dataIntegrityIs409WithoutLeakingSql() {
        var problem = handler.integrity(new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"branch_name_key\"]"));
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(code(problem)).isEqualTo(ErrorCodes.DATA_CONFLICT);
        assertThat(problem.getDetail()).isEqualTo("The change conflicts with existing data").doesNotContain("branch_name_key");
    }

    /**
     * An unexpected exception is 500 with a fixed message: the exception's own text must not reach the client.
     *
     * @throws Exception never, for a non-security exception
     */
    @Test
    void unexpectedExceptionIs500WithFixedMessage() throws Exception {
        var problem = handler.unexpected(new IllegalStateException("password=hunter2 jdbc:postgresql://db/prod"));
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(code(problem)).isEqualTo(ErrorCodes.INTERNAL_ERROR);
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred");
    }

    /** Security exceptions are re-thrown so the security filter chain still answers 403 and 401. */
    @Test
    void securityExceptionsAreRethrown() {
        var denied = new AccessDeniedException("Only the owner can cancel a delivery");
        assertThatThrownBy(() -> handler.unexpected(denied)).isSameAs(denied);
        var bad = new BadCredentialsException("no");
        assertThatThrownBy(() -> handler.unexpected(bad)).isSameAs(bad);
    }

    /**
     * A failed body validation is 400 {@code VALIDATION_FAILED} listing every field in the {@code field message}
     * format the frontend splits on.
     *
     * @throws Exception when the test fixture cannot be built
     */
    @Test
    void validationFailureListsAllFields() throws Exception {
        var target = new Object();
        var errors = new BeanPropertyBindingResult(target, "body");
        errors.addError(new FieldError("body", "name", "must not be blank"));
        errors.addError(new FieldError("body", "unit", "size must be between 0 and 20"));
        var parameter = new MethodParameter(getClass().getDeclaredMethod("endpoint", Object.class), 0);

        var response = handler.handleMethodArgumentNotValid(new MethodArgumentNotValidException(parameter, errors),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(new MockHttpServletRequest()));

        var problem = (ProblemDetail) response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem).isNotNull();
        assertThat(code(problem)).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThat(problem.getDetail()).isEqualTo("name must not be blank; unit size must be between 0 and 20");
    }

    /**
     * Stand-in for a controller method, only there to give {@link MethodParameter} something to point at.
     *
     * @param body ignored
     */
    void endpoint(Object body) {}
}
