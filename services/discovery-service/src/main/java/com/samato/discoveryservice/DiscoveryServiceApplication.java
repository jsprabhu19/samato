package com.samato.discoveryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Netflix Eureka — the service registry.
 *
 * How it works (the elevator pitch for the interview):
 *   1. On startup, every service sends a heartbeat with its name + URL.
 *   2. Eureka stores it in an in-memory map: serviceName -> [urls].
 *   3. The API gateway and inter-service callers ask Eureka for "where is
 *      order-service?" and Eureka returns a healthy instance.
 *   4. If a service stops heartbeating, Eureka evicts it; the load
 *      balancer stops sending traffic to it.
 *
 * Alternatives: Consul, Zookeeper, etcd. Trade-offs in the interview notes.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
}
