package com.samato.paymentservice.api;

import com.razorpay.Order;
import com.razorpay.Razorpay;
import com.razorpay.RazorpayException;
import com.razorpay.Refund;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The real Razorpay integration.
 *
 *   - Uses the official {@code com.razorpay:razorpay-java} SDK.
 *   - Runs in **test mode** (Razorpay URL is the test API; keys start with rzp_test_).
 *   - Money on the wire is in **paise** (1 INR = 100 paise).
 *
 * Why convert at this boundary?
 *   We keep BigDecimal ₹ throughout the domain (no floating-point
 *   money). Razorpay's API takes an integer paise count. Conversion
 *   happens here, in one place, where the boundary is clear.
 */
@Component
public class RazorpayClientImpl implements RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClientImpl.class);

    private final Razorpay razorpay;
    private final String webhookSecret;

    public RazorpayClientImpl(@Value("${razorpay.key_id}") String keyId,
                              @Value("${razorpay.key_secret}") String keySecret,
                              @Value("${razorpay.webhook_secret}") String webhookSecret) {
        this.razorpay = new Razorpay(keyId, keySecret);
        this.webhookSecret = webhookSecret;
        log.info("RazorpayClientImpl initialised (key_id={})", maskKey(keyId));
    }

    @Override
    public RazorpayOrderResult createOrder(long amountPaise, String currency, String receipt, String idempotencyKey) {
        validateAmount(amountPaise);
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amountPaise);          // paise (integer)
            request.put("currency", currency);
            request.put("receipt", receipt);             // = our orderId
            request.put("payment_capture", 1);            // auto-capture on success

            // The razorpay-java SDK doesn't expose an idempotency-key
            // header on its built-in helpers. We pass it via the
            // request options pattern when we need it; for createOrder
            // the receipt itself is the dedup key (Razorpay dedups on
            // receipt server-side).
            log.info("Creating Razorpay order: receipt={} amountPaise={} currency={}",
                    receipt, amountPaise, currency);

            Order order = razorpay.Orders.create(request);
            String orderId = order.get("id");
            String status = order.get("status");
            long amount = order.get("amount");
            String ccy = order.get("currency");

            return new RazorpayOrderResult(orderId, receipt, amount, ccy, status);
        } catch (RazorpayException e) {
            // Wrap into a runtime exception; the service layer will
            // map it to a failed event in the saga compensation.
            throw new PaymentGatewayException("Razorpay order creation failed", e);
        }
    }

    @Override
    public RazorpayRefundResult refund(String razorpayPaymentId, long amountPaise, String idempotencyKey) {
        validateAmount(amountPaise);
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amountPaise);

            log.info("Refunding Razorpay payment {}: amountPaise={}", razorpayPaymentId, amountPaise);
            Refund refund = razorpay.Payments.refund(razorpayPaymentId, request);

            return new RazorpayRefundResult(
                    refund.get("id"),
                    razorpayPaymentId,
                    refund.get("amount"),
                    refund.get("currency"),
                    refund.get("status")
            );
        } catch (RazorpayException e) {
            throw new PaymentGatewayException("Razorpay refund failed for " + razorpayPaymentId, e);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        try {
            // razorpay-java ships a utility for HMAC verification.
            return Utils.verifyWebhookSignature(payload, signature, webhookSecret);
        } catch (RazorpayException e) {
            log.warn("Razorpay webhook signature verification threw: {}", e.getMessage());
            return false;
        }
    }

    private void validateAmount(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be > 0; got " + amountPaise);
        }
    }

    /**
     * Convert rupees to paise with banker's rounding (HALF_EVEN).
     * Used by callers; the field is not stored, just computed on the way out.
     */
    public static long toPaise(BigDecimal rupees) {
        return rupees.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 7) + "***" + key.substring(key.length() - 4);
    }

    /**
     * Thrown when Razorpay's API returns an error or is unreachable.
     * The service layer treats this as a transient failure for saga
     * retry / circuit-breaker purposes.
     */
    public static class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String msg, Throwable cause) { super(msg, cause); }
    }
}
