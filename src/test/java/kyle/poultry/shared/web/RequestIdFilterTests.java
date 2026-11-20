package kyle.poultry.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests of {@link RequestIdFilter} with Spring's mock servlet objects: no server, no database. */
class RequestIdFilterTests {
    final RequestIdFilter filter = new RequestIdFilter();

    /** With no incoming id, a new UUID is generated, put in the log context during the request, and echoed. */
    @Test
    void generatesAnIdAndExposesItInTheLogContextDuringTheRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/products");
        var response = new MockHttpServletResponse();
        var seenInChain = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID)));

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(seenInChain.get()).isEqualTo(id);
    }

    /** A caller-supplied safe id is kept, so a frontend can correlate its own logs with the server's. */
    @Test
    void keepsASafeSuppliedId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(RequestIdFilter.HEADER, "web-4f2a.9_Z");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("web-4f2a.9_Z");
    }

    /** Unsafe ids (log forging, markup, too long, empty) are replaced, never echoed. */
    @ParameterizedTest
    @ValueSource(strings = {"has space", "line\\nbreak", "<script>alert(1)</script>", "semi;colon", "", "x"})
    void replacesUnsafeIds(String supplied) throws Exception {
        // "x" is safe; it is kept. The other values are not.
        var request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(RequestIdFilter.HEADER, supplied);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader(RequestIdFilter.HEADER);
        if (supplied.equals("x")) assertThat(id).isEqualTo("x");
        else assertThat(id).isNotEqualTo(supplied).matches("[0-9a-f-]{36}");
    }

    /** An id longer than 64 characters is replaced. */
    @Test
    void replacesTooLongIds() {
        assertThat(RequestIdFilter.resolveId("a".repeat(64))).isEqualTo("a".repeat(64));
        assertThat(RequestIdFilter.resolveId("a".repeat(65))).isNotEqualTo("a".repeat(65));
    }

    /** The log context is cleared after the request, even when the rest of the chain throws, so ids never leak across requests. */
    @Test
    void clearsTheLogContextEvenWhenTheChainFails() {
        var request = new MockHttpServletRequest("GET", "/api/products");
        var response = new MockHttpServletResponse();
        try {
            filter.doFilter(request, response, (req, res) -> {
                MDC.put(RequestIdFilter.MDC_USER_ID, "7");
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_USER_ID)).isNull();
    }
}
