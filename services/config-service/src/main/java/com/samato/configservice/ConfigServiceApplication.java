package com.samato.configservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server.
 *
 * @see <a href="https://spring.io/projects/spring-cloud-config">Spring Cloud Config</a>
 *
 * Interview talking points:
 *   Q: "Why a config server? Why not env vars?"
 *   A: Env vars are good for ENV-specific values, but config changes
 *      would require redeploys. Config server gives us:
 *      - Centralized, auditable history (it's just a Git repo).
 *      - Hot refresh (services can /actuator/refresh).
 *      - Profile-based config (application-{profile}.yml).
 *      - Encrypted properties (with Spring Cloud Config + a KMS).
 *
 *   Q: "What about secrets?"
 *   A: Config server is NOT a secret store. Secrets stay in Vault/AWS
 *      Secrets Manager and are resolved by services at startup, not
 *      committed to Git. This server is for non-secret config.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServiceApplication.class, args);
    }
}
