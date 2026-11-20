package kyle.poultry.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request an id, puts it in the log context and echoes it in the {@value #HEADER} response header,
 * then writes one access-log line when the request ends.
 *
 * <p>It runs before the security filter chain, so even 401 and 403 answers carry the id. A caller may supply its own
 * id (so a frontend can correlate its own logs); it is accepted only if it is short and made of safe characters,
 * otherwise a new one is generated, so a client can never inject line breaks or markup into the logs.
 *
 * <p>Public because other modules read {@link #MDC_REQUEST_ID} to stamp their records; it holds no state.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    /** Name of the request and response header that carries the id. */
    public static final String HEADER = "X-Request-Id";
    /** Log-context (MDC) key holding the current request id. */
    public static final String MDC_REQUEST_ID = "requestId";
    /** Log-context (MDC) key holding the authenticated user's id; set by the identity module once known. */
    public static final String MDC_USER_ID = "userId";

    /** Accepts 1 to 64 letters, digits, dots, underscores and hyphens; anything else is replaced. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger access = LoggerFactory.getLogger("kyle.poultry.access");

    /**
     * Wraps the rest of the chain with the id, the response header and the access-log line.
     *
     * @param request  the incoming request
     * @param response the outgoing response
     * @param chain    the remaining filters
     * @throws ServletException if a later filter fails
     * @throws IOException      if reading or writing fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolveId(request.getHeader(HEADER));
        MDC.put(MDC_REQUEST_ID, id);
        // Set before the chain runs so the header is present even if the response is committed early (for example 401).
        response.setHeader(HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Path only, never the query string: queries can hold customer names or other personal data.
            access.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
            // Always clear, because servlet threads are reused for the next request.
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    /**
     * Chooses the id for a request.
     *
     * @param supplied the value of the incoming header, or null
     * @return the supplied value when it is safe, otherwise a new random UUID
     */
    static String resolveId(String supplied) {
        // An unsafe or missing id is replaced rather than rejected: the request itself is still valid.
        return supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }
}
