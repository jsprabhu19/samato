package com.samato.shared.observability;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import java.util.UUID;

/**
 * Propagates the correlation id (and Micrometer trace/span ids) from the
 * current thread's MDC onto every outgoing Feign call.
 *
 * Why a separate interceptor from JWT forwarding?
 *   The auth-forwarder deals with the Authorization header (security
 *   concern). This one deals with observability. They run in the same
 *   Feign pipeline but solve different problems.
 *
 * If MDC is empty (background poller thread, scheduler), we generate a
 * fresh correlation id and put it in MDC. That way the id is still
 * present in the downstream service's MDC — just unattached to a user
 * request.
 */
public class FeignCorrelationIdInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        }
        template.header(CorrelationIdFilter.HEADER, correlationId);

        copyIfPresent(MdcKeys.TRACE_ID, template);
        copyIfPresent(MdcKeys.SPAN_ID, template);
    }

    private static void copyIfPresent(String mdcKey, RequestTemplate template) {
        String v = MDC.get(mdcKey);
        if (v != null && !v.isBlank()) {
            template.header("X-" + mdcKey, v);
        }
    }
}
