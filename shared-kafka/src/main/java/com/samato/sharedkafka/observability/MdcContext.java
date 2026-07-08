package com.samato.sharedkafka.observability;

import com.samato.shared.observability.MdcKeys;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Installs MDC values for the duration of a unit of work, then clears
 * them. Use in Kafka listeners:
 *
 * <pre>{@code
 * @KafkaListener(...)
 * public void onEvent(ConsumerRecord<String, ?> record) {
 *     try (var ignored = MdcContext.fromKafka(record)) {
 *         log.info("processing event");  // will include correlationId
 *         // ... do the work
 *     }
 * }
 * }</pre>
 *
 * Why try-with-resources? Because the consumer thread is pooled and
 * reused; if we forget to clear MDC, the next record's MDC would
 * contaminate this one.
 */
public final class MdcContext implements AutoCloseable {

    private final Map<String, String> previous;

    private MdcContext(Map<String, String> previous) {
        this.previous = previous;
    }

    public static MdcContext fromKafka(ConsumerRecord<?, ?> record) {
        Map<String, String> prev = new HashMap<>();
        putIfPresent(prev, MdcKeys.CORRELATION_ID, headerString(record, "X-Correlation-Id"));
        putIfPresent(prev, MdcKeys.TRACE_ID, headerString(record, "X-Trace-Id"));
        putIfPresent(prev, MdcKeys.SPAN_ID, headerString(record, "X-Span-Id"));
        return new MdcContext(prev);
    }

    public static MdcContext fresh() {
        return new MdcContext(new HashMap<>());
    }

    @Override
    public void close() {
        for (var e : previous.entrySet()) {
            if (e.getValue() == null) MDC.remove(e.getKey());
            else MDC.put(e.getKey(), e.getValue());
        }
    }

    private static String headerString(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value());
    }

    private static void putIfPresent(Map<String, String> prev, String key, String value) {
        prev.put(key, MDC.get(key));
        if (value != null) MDC.put(key, value);
        else MDC.remove(key);
    }
}
