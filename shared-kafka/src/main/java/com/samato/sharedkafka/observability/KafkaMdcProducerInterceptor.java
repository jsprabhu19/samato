package com.samato.sharedkafka.observability;

import com.samato.shared.observability.MdcKeys;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka producer interceptor that stamps the current MDC correlationId
 * (and traceId/spanId) onto the outgoing record's headers. The
 * consumer-side {@link KafkaMdcConsumerInterceptor} reads them back and
 * puts them into the consumer thread's MDC.
 *
 * We do this via Kafka headers (not a side channel) because Kafka
 * headers survive serialization and are visible to every consumer
 * (including ones we haven't written yet).
 */
public class KafkaMdcProducerInterceptor implements ProducerInterceptor<Object, Object> {

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        }
        record.headers().add("X-Correlation-Id", correlationId.getBytes());

        addIfPresent(record, "X-Trace-Id", MDC.get(MdcKeys.TRACE_ID));
        addIfPresent(record, "X-Span-Id", MDC.get(MdcKeys.SPAN_ID));
        return record;
    }

    @Override public void onAcknowledgement(RecordMetadata m, Exception e) { }
    @Override public void close() { }
    @Override public void configure(Map<String, ?> configs) { }

    private static void addIfPresent(ProducerRecord<?, ?> r, String name, String v) {
        if (v != null && !v.isBlank()) r.headers().add(name, v.getBytes());
    }
}
