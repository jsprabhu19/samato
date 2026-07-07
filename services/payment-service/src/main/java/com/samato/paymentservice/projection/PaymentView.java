package com.samato.paymentservice.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The CQRS read model for payments.
 *
 *   - Written by {@link PaymentProjector} (in-process, in the same txn as the
 *     command handler).
 *   - Read by the REST API for "what's the status of payment X?".
 *
 * This is a denormalised view: it has both the order id (for
 * cross-service lookup) and the Razorpay-side ids (for webhook
 * reconciliation).
 */
@Entity
@Table(name = "payment_view")
public class PaymentView {

    @Id
    @Column(name = "payment_id", columnDefinition = "uuid")
    private UUID paymentId;

    @Column(name = "razorpay_order_id", length = 40)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 40)
    private String razorpayPaymentId;

    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    private UUID customerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private com.samato.paymentservice.domain.PaymentStatus status;

    @Column(name = "last_event_seq", nullable = false)
    private long lastEventSeq;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public PaymentView() {}

    // -- accessors / setters (read model; the projector is the only writer)
    public UUID getPaymentId()           { return paymentId; }
    public void setPaymentId(UUID v)     { this.paymentId = v; }
    public String getRazorpayOrderId()   { return razorpayOrderId; }
    public void setRazorpayOrderId(String v) { this.razorpayOrderId = v; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v) { this.razorpayPaymentId = v; }
    public UUID getOrderId()            { return orderId; }
    public void setOrderId(UUID v)      { this.orderId = v; }
    public UUID getCustomerId()         { return customerId; }
    public void setCustomerId(UUID v)   { this.customerId = v; }
    public BigDecimal getAmount()       { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency()         { return currency; }
    public void setCurrency(String v)   { this.currency = v; }
    public com.samato.paymentservice.domain.PaymentStatus getStatus() { return status; }
    public void setStatus(com.samato.paymentservice.domain.PaymentStatus v) { this.status = v; }
    public long getLastEventSeq()       { return lastEventSeq; }
    public void setLastEventSeq(long v) { this.lastEventSeq = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
