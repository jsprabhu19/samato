package com.samato.apigateway.config;

import com.samato.shared.observability.CorrelationIdFilter;
import com.samato.shared.observability.MdcKeys;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive equivalent of {@link CorrelationIdFilter} for the gateway.
 *
 * The shared-module filter extends {@code OncePerRequestFilter} which is
 * servlet API; it never runs on the WebFlux stack. Without THIS filter,
 * every log line emitted by the gateway would lack a correlationId, and
 * the downstream services would each invent their own (instead of
 * inheriting the one started at the edge).
 *
 * Runs at {@code HIGHEST_PRECEDENCE} so the MDC is populated before
 * Spring Security, Gateway routing, or any other filter logs anything.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String incoming = req.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        String id = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString()
                : incoming;

        MDC.put(MdcKeys.CORRELATION_ID, id);
        exchange.getResponse().getHeaders().add(CorrelationIdFilter.HEADER, id);

        return chain.filter(exchange).doFinally(sig -> MDC.remove(MdcKeys.CORRELATION_ID));
    }
}
