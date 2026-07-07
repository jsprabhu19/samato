package com.samato.paymentservice.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.paymentservice.api.RazorpayClient;
import com.samato.paymentservice.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay webhook receiver.
 *
 *   - No JWT — Razorpay doesn't have our JWT. We use HMAC SHA-256
 *     signature verification instead.
 *   - The endpoint is in a separate Spring Security filter chain
 *     ({@link com.samato.paymentservice.security.RazorpayWebhookSecurityConfig})
 *     that allows permitAll; the signature check inside this method
 *     is the real auth.
 *
 * Why 200 OK on a bad signature?
 *   We return 401 instead. Razorpay will retry on non-2xx — which is
 *   what we want if the signature check failed, because that means
 *   the request was tampered with.
 */
@RestController
@RequestMapping("/api/payments/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final RazorpayClient razorpay;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public WebhookController(RazorpayClient razorpay,
                             PaymentService paymentService,
                             ObjectMapper objectMapper) {
        this.razorpay = razorpay;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Void> handle(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (signature == null || signature.isBlank()) {
            log.warn("Razorpay webhook missing signature header");
            return ResponseEntity.status(401).build();
        }
        if (!razorpay.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Razorpay webhook signature verification failed");
            return ResponseEntity.status(401).build();
        }
        try {
            JsonNode event = objectMapper.readTree(rawBody);
            paymentService.handleWebhook(rawBody, event);
        } catch (Exception e) {
            log.error("Razorpay webhook processing error: {}", e.getMessage(), e);
            // Re-throwing would propagate to Spring's error handler.
            // We return 500 so Razorpay retries the delivery.
            return ResponseEntity.status(500).build();
        }
        return ResponseEntity.ok().build();
    }
}
