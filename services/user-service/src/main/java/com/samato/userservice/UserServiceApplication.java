package com.samato.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * User Service — the profile side of identity.
 *
 * Why split from auth-service?
 *   - **Different SLAs.** Auth must always be up; profiles are read-mostly
 *     and can be eventually consistent.
 *   - **Different data.** Auth = email + bcrypt + roles (PII-light).
 *     Profile = name, photo, address, preferences (PII-heavy).
 *   - **Different teams.** A privacy/security team owns auth; the product
 *     team owns profiles. Separation of concerns.
 *   - **Different scaling.** Auth is write-light. Profile reads spike
 *     (every order summary fetches the customer name).
 *
 * The two services are joined by `userId` (UUID). User-service treats
 * the id as opaque — it doesn't know or care that auth-service uses
 * BCrypt or what the auth DB schema looks like.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
