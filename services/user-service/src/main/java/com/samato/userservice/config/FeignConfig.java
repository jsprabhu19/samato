package com.samato.userservice.config;

import com.samato.shared.observability.FeignCorrelationIdInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the shared {@link FeignCorrelationIdInterceptor} as a bean so
 * Spring Cloud OpenFeign picks it up automatically. Without this, the
 * {@code AuthClient} Feign interface would generate a fresh correlationId
 * on every call, breaking cross-service log correlation.
 */
@Configuration
public class FeignConfig {

    @Bean
    public FeignCorrelationIdInterceptor correlationIdInterceptor() {
        return new FeignCorrelationIdInterceptor();
    }
}
