package de.joinside.evmap_service.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTests {
    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void generatesCorrelationIdAndEchoesItOnTheResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/stations"), response, new MockFilterChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
    }

    @Test
    void keepsAnInboundCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stations");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "client-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("client-supplied-id");
    }

    @Test
    void clearsTheDiagnosticContextEvenWhenTheChainThrows() {
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                throw new IllegalStateException("boom");
            }
        };

        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/stations"), new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            // Rethrown on purpose so the container's error handling still applies.
        }

        // Request threads are pooled — a leaked context would mislabel the next request.
        assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
        assertThat(MDC.get(LogContext.HTTP_PATH)).isNull();
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void skipsHealthChecks() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response, new MockFilterChain());

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNull();
    }
}
