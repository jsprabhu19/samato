package com.samato.shared.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * MDC keys we propagate into every log line so a single request
 * is correlatable across services.
 *
 * Why?
 *   - `traceId` / `spanId` come from Micrometer Tracing automatically,
 *     but exposing them as MDC keys means Logback can print them
 *     in EVERY log line without us having to pass them around.
 *   - `userId` lets us grep logs for "what was user 7 doing in the
 *     last hour?" — invaluable in production.
 *
 * Interview tip: this is the simplest observability win you can ship.
 */
public final class MdcKeys {
    public static final String USER_ID = "userId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID  = "spanId";

    private MdcKeys() {}

    public static String newCorrelationId() {
        String id = UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID, id);
        return id;
    }
}
