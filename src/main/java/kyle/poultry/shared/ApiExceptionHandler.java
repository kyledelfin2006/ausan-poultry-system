package kyle.poultry.shared;

import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions thrown anywhere in the application into problem-detail HTTP responses.
 * <p>
 * It is the single place that decides which exception becomes which status code, so modules only throw
 * the shared exception types ({@link NotFoundException}, {@link RuleViolation}, {@link ConflictException})
 * and never build HTTP responses themselves. It is package-private: Spring finds it by component scanning.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    /**
     * Maps a missing record to HTTP 404.
     *
     * @param e the exception raised when an id does not exist
     * @return a problem detail with status 404 and the exception message as detail
     */
    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Maps a broken business rule to HTTP 422 (the request is well-formed but not acceptable).
     *
     * @param e the exception raised when a business rule is violated
     * @return a problem detail with status 422 and the exception message as detail
     */
    @ExceptionHandler(RuleViolation.class)
    ProblemDetail rule(RuleViolation e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /**
     * Maps a clash with the current state (for example insufficient stock) to HTTP 409.
     *
     * @param e the conflict exception, including subclasses such as {@code InsufficientStockException}
     * @return a problem detail with status 409 and the exception message as detail
     */
    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Maps a database constraint failure (duplicate name, foreign key, check constraint) to HTTP 409.
     * A fixed message is returned so no SQL or table detail leaks to the client.
     *
     * @param e the persistence exception raised by the database constraint
     * @return a problem detail with status 409 and a generic detail message
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The change conflicts with existing data");
    }

    /**
     * Maps a failed {@code @Valid} request-body check to HTTP 400, listing every offending field.
     *
     * @param e the validation exception carrying one error per invalid field
     * @return a problem detail with status 400 whose detail reads {@code field message; field message}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        // Join all field errors into one readable line so the client sees every problem at once.
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
