package kyle.poultry.shared;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Guarantees that every {@link ProblemDetail} written by the application has a {@code code}.
 * <p>
 * {@link ApiExceptionHandler} sets specific codes for the exceptions it knows. Problem details created elsewhere (Spring
 * MVC's built-in 405/415 responses, the 429 built by the login controller) reach the client through this advice, which
 * fills in the generic code for their HTTP status. A code that is already set is never overwritten. Package-private;
 * Spring finds it by component scanning.
 */
@RestControllerAdvice
class ProblemCodeAdvice implements ResponseBodyAdvice<Object> {

    /** Name of the extension member that carries the error code in the JSON body. */
    static final String PROPERTY = "code";

    /**
     * Applies to every handler return type: the body type is only known at write time (for example inside a
     * {@code ResponseEntity<?>}), so the real check happens in {@link #beforeBodyWrite}.
     *
     * @param returnType    the controller or handler method's return type (ignored)
     * @param converterType the converter that will write the body (ignored)
     * @return always true
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * Adds the status-derived code to a problem detail that has none; any other body passes through unchanged.
     *
     * @param body        the value about to be serialised
     * @param returnType  the handler method's return type (ignored)
     * @param contentType the chosen content type (ignored)
     * @param converter   the chosen converter (ignored)
     * @param request     the current request (ignored)
     * @param response    the current response (ignored)
     * @return the same body, with {@code code} set when it is a {@link ProblemDetail}
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(PROPERTY))) {
            // Only fill a gap: a specific code chosen by the handler must survive.
            problem.setProperty(PROPERTY, ErrorCodes.forStatus(HttpStatusCode.valueOf(problem.getStatus())));
        }
        return body;
    }
}
