package com.rag.springai.insuranceai.infrastructure.observability;

import com.rag.springai.insuranceai.domain.shared.TraceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Assigns a {@link TraceId} to every incoming HTTP request (reusing one supplied via the
 * {@value #TRACE_ID_HEADER} header, or generating one), publishes it to the logging MDC for
 * the duration of the request, and echoes it back on the response so callers can correlate
 * logs, audit events and API responses (brief section 22, 29, 38).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        TraceId traceId = (incoming == null || incoming.isBlank()) ? TraceId.generate() : TraceId.of(incoming);
        MDC.put(TraceId.MDC_KEY, traceId.value());
        response.setHeader(TRACE_ID_HEADER, traceId.value());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceId.MDC_KEY);
        }
    }
}
