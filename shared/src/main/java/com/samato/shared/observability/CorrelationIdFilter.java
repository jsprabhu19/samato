package com.samato.shared.observability;

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
import java.util.UUID;

/**
 * Reads (or generates) an `X-Correlation-Id` header for every request
 * and stuffs it into MDC so it appears in every log line.
 *
 * Why a separate id from the traceId?
 *   - The traceId is per-call-chain (a single client request is one trace).
 *   - The correlationId can survive across multiple touch points (e.g. an
 *     async saga span might span several traces). Some teams use correlation
 *     as a "user-visible" ticket id.
 *
 * In practice: set the X-Correlation-Id on the response so the client
 * can quote it when reporting a problem.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MdcKeys.CORRELATION_ID, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
        }
    }
}
