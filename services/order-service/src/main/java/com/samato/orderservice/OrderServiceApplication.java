package com.samato.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order Service — the heart of Samato.
 *
 * This service owns the order lifecycle and runs the **Saga orchestrator**
 * for the order-to-delivery workflow. The saga coordinates the steps:
 *
 *   1. VALIDATE_RESTAURANT     (sync, restaurant-service)
 *   2. VALIDATE_ITEMS          (sync, restaurant-service — menu & prices)
 *   3. RESERVE_INVENTORY       (Phase 5 will add real stock; Phase 4 stubs it)
 *   4. CHARGE_PAYMENT          (Phase 5; Phase 4 stubs it)
 *   5. CONFIRM_ORDER           (state transition in DB + outbox)
 *
 * If any step fails, the saga runs **compensations** in reverse:
 *
 *   5. (no compensation needed)
 *   4. REFUND_PAYMENT          (stub)
 *   3. RELEASE_INVENTORY       (stub)
 *   2. (no compensation)
 *   1. (no compensation)
 *
 * Why orchestrator (not choreography)?
 *   - The flow is explicit and visible in code (the State enum + the step list).
 *   - Easier to add steps without touching other services.
 *   - Easier to reason about in interviews: "here's the state machine, here
 *     are the transitions, here are the compensations."
 *   - The trade-off: the orchestrator is the bottleneck and a coupling
 *     point. Choreography is more decoupled but harder to debug.
 *
 * The saga state is persisted in the `saga_instance` table so a crashed
 * orchestrator can be restarted and the saga resumes from where it left off.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@ComponentScan(basePackages = {
        "com.samato.orderservice",
        "com.samato.shared",
        "com.samato.sharedkafka"
})
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
