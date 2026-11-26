package kyle.poultry.shared;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions thrown anywhere in the application into problem-detail HTTP responses, each carrying a stable
 * machine-readable {@code code} (see {@link ErrorCodes}).
 * <p>
 * It is the single place that decides which exception becomes which status code, so modules only throw
 * the shared exception types ({@link NotFoundException}, {@link RuleViolation}, {@link ConflictException})
 * and never build HTTP responses themselves. Extending {@link ResponseEntityExceptionHandler} also brings Spring MVC's
 * own failures (unreadable JSON, wrong parameter type, 405, 415, {@code ResponseStatusException}) into the same
 * {@code application/problem+json} shape instead of Spring Boot's default error JSON. Problem details that are built by
 * hand elsewhere (the login throttle's 429) receive their {@code code} from {@link ProblemCodeAdvice}.
 * <p>
 * Not handled here, by design: 401 and 403 raised by the security layer are plain responses with an empty body (they
 * never reach controller advice), so for those the HTTP status is the contract. It is package-private: Spring finds it
 * by component scanning.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Builds a problem detail with a status, a human-readable message and a machine-readable code.
     *
     * @param status the HTTP status
     * @param detail the message for the user
     * @param code   one of the {@link ErrorCodes} constants
     * @return the problem detail, with {@code code} as an extension property
     */
    private static ProblemDetail problem(HttpStatus status, String detail, String code) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        // "code" is a flat extension member of the RFC 9457 object, next to status/title/detail.
        problem.setProperty(ProblemCodeAdvice.PROPERTY, code);
        return problem;
    }

    /**
     * Maps a missing record to HTTP 404.
     *
     * @param e the exception raised when an id does not exist
     * @return a problem detail with status 404, code {@code NOT_FOUND} and the exception message as detail
     */
    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage(), ErrorCodes.NOT_FOUND);
    }

    /**
     * Maps a broken business rule to HTTP 422 (the request is well-formed but not acceptable).
     *
     * @param e the exception raised when a business rule is violated
     * @return a problem detail with status 422, code {@code RULE_VIOLATION} and the exception message as detail
     */
    @ExceptionHandler(RuleViolation.class)
    ProblemDetail rule(RuleViolation e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), ErrorCodes.RULE_VIOLATION);
    }

    /**
     * Maps a clash with the current state (for example insufficient stock) to HTTP 409.
     *
     * @param e the conflict exception, including subclasses such as {@code InsufficientStockException}
     * @return a problem detail with status 409, code {@code CONFLICT} and the exception message as detail
     */
    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage(), ErrorCodes.CONFLICT);
    }

    /**
     * Maps a repeat that arrived while the first identical request is still running to HTTP 409 with {@code Retry-After}.
     *
     * @param e the in-progress exception
     * @return a 409 problem detail with code {@code CONFLICT} and a {@code Retry-After} header
     */
    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<ProblemDetail> inProgress(RequestInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RequestInProgressException.RETRY_AFTER_SECONDS))
                .body(problem(HttpStatus.CONFLICT, e.getMessage(), ErrorCodes.CONFLICT));
    }

    /**
     * Maps a database constraint failure (duplicate name, foreign key, check constraint) to HTTP 409.
     * A fixed message is returned so no SQL or table detail leaks to the client.
     *
     * @param e the persistence exception raised by the database constraint
     * @return a problem detail with status 409, code {@code DATA_CONFLICT} and a generic detail message
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "The change conflicts with existing data", ErrorCodes.DATA_CONFLICT);
    }

    /**
     * Last resort for anything not mapped above: HTTP 500 with a fixed message, never the exception text.
     * <p>
     * Security exceptions are re-thrown untouched: an {@link AccessDeniedException} raised inside a service must still
     * reach Spring Security's own handler (403) and an {@link AuthenticationException} its entry point (401); turning
     * them into 500 would be a regression.
     *
     * @param e the unexpected exception
     * @return a problem detail with status 500 and code {@code INTERNAL_ERROR}
     * @throws Exception the same exception again, when it is a security exception
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) throws Exception {
        // Hand security exceptions back so the security filter chain answers 401/403 as before.
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) throw e;
        // The stack trace goes to the log only; the client gets nothing that could leak internals.
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", ErrorCodes.INTERNAL_ERROR);
    }

    /**
     * Maps a failed {@code @Valid} request-body check to HTTP 400, listing every offending field.
     * Overrides the framework default so the detail keeps the project's {@code field message; field message} format.
     *
     * @param e       the validation exception carrying one error per invalid field
     * @param headers headers to write to the response
     * @param status  the status chosen by the framework (400)
     * @param request the current request
     * @return a response with a problem detail of status 400, code {@code VALIDATION_FAILED}, whose detail reads
     *         {@code field message; field message}
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        // Join all field errors into one readable line so the client sees every problem at once.
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return handleExceptionInternal(e, problem(HttpStatus.BAD_REQUEST, detail, ErrorCodes.VALIDATION_FAILED),
                headers, status, request);
    }
}
