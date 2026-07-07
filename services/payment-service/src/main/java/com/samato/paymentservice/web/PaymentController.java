package com.samato.paymentservice.web;

import com.samato.paymentservice.api.PaymentDtos;
import com.samato.paymentservice.api.PaymentDtos.BalanceAtResponse;
import com.samato.paymentservice.api.PaymentDtos.CreatePaymentRequest;
import com.samato.paymentservice.api.PaymentDtos.PaymentResponse;
import com.samato.paymentservice.api.PaymentDtos.RefundRequest;
import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.domain.command.PaymentCommand;
import com.samato.paymentservice.query.PaymentQueryService;
import com.samato.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the payment-service.
 *
 * The webhook endpoint lives in {@link WebhookController} because it
 * has different security (HMAC instead of JWT) and is mounted under
 * a separate Spring Security filter chain.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentQueryService queryService;

    public PaymentController(PaymentService paymentService, PaymentQueryService queryService) {
        this.paymentService = paymentService;
        this.queryService = queryService;
    }

    /**
     * Create a Razorpay order. Called by the order-service saga.
     */
    @PostMapping("/orders")
    public ResponseEntity<PaymentResponse> createOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UUID paymentId = UUID.nameUUIDFromBytes(
                ("payment:" + req.orderId()).getBytes());
        PaymentCommand.CreateRazorpayOrder cmd = new PaymentCommand.CreateRazorpayOrder(
                UUID.randomUUID(),
                paymentId,
                req.orderId(),
                req.customerId(),
                Money.of(req.amount(), req.currency()),
                req.currency()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createRazorpayOrder(cmd, idempotencyKey));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return queryService.findById(id);
    }

    @GetMapping("/by-order/{orderId}")
    public PaymentResponse getByOrder(@PathVariable UUID orderId) {
        return queryService.findByOrderId(orderId);
    }

    /**
     * The full event stream for a payment. For debugging / audit.
     */
    @GetMapping("/{id}/events")
    public List<PaymentDtos.EventResponse> events(@PathVariable UUID id) {
        return queryService.loadEvents(id).stream()
                .map(e -> new PaymentDtos.EventResponse(0, e.version(), e.getClass().getSimpleName(), null))
                .toList();
    }

    /**
     * Time-travel: state at version N.
     */
    @GetMapping("/{id}/balance-at/{version}")
    public BalanceAtResponse balanceAt(@PathVariable UUID id, @PathVariable int version) {
        Money m = queryService.balanceAt(id, version);
        return BalanceAtResponse.of(id, version, m);
    }

    /**
     * Refund a captured payment. Admin-only.
     */
    @PostMapping("/{id}/refunds")
    public PaymentResponse refund(@PathVariable UUID id,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody RefundRequest req) {
        Payment p = queryService.loadAggregate(id);
        PaymentCommand.RefundPayment cmd = new PaymentCommand.RefundPayment(
                UUID.randomUUID(),
                id,
                Money.of(req.amount(), p.getCurrency())
        );
        return paymentService.refund(cmd, idempotencyKey);
    }
}
