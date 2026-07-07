package com.samato.paymentservice.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Standalone HMAC-SHA256 webhook signature verifier.
 *
 * Razorpay signs the raw webhook body with the webhook secret using
 * HMAC-SHA256 and sends the hex digest in the
 * {@code X-Razorpay-Signature} header. We MUST verify this before
 * trusting any payload.
 *
 * Why a custom verifier when {@code Utils.verifyWebhookSignature} exists?
 * Two reasons:
 *
 *   1. The Razorpay SDK's utility throws {@code RazorpayException} on
 *      any internal error. We want a clean boolean.
 *   2. We want constant-time comparison ourselves (the SDK uses
 *      {@code MessageDigest.isEqual} which is constant-time, but
 *      having it in our own code makes the security boundary
 *      auditable).
 *
 * Kept as a thin static helper; the {@link RazorpayClientImpl} also
 * delegates to {@code Utils.verifyWebhookSignature}, so this is a
 * belt-and-braces secondary check available for direct use.
 */
public final class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final String ALGO = "HmacSHA256";

    private WebhookSignatureVerifier() {}

    public static boolean verify(String secret, String payload, String expectedSignatureHex) {
        if (secret == null || secret.isBlank()) {
            log.error("Webhook secret is not configured — rejecting all webhooks");
            return false;
        }
        if (expectedSignatureHex == null || expectedSignatureHex.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String actualHex = toHex(digest);
            return constantTimeEquals(actualHex, expectedSignatureHex);
        } catch (Exception e) {
            log.error("HMAC computation failed", e);
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Constant-time string comparison. Avoids early-exit timing
     * attacks where an attacker measures response time to guess
     * prefix bytes of the signature.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
