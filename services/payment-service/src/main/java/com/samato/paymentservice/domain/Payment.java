package com.samato.paymentservice.domain;

import com.samato.paymentservice.eventstore.snapshot.Snapshotter;
import com.samato.paymentservice.domain.command.PaymentCommand;
import com.samato.paymentservice.domain.event.PaymentEvent;
import com.samato.paymentservice.domain.event.PaymentExpired;
import com.samato.paymentservice.domain.event.PaymentFailed;
import com.samato.paymentservice.domain.event.PaymentInitiated;
import com.samato.paymentservice.domain.event.RazorpayOrderCreated;
import com.samato.paymentservice.domain.event.RefundCompleted;
import com.samato.paymentservice.domain.event.RefundInitiated;
import com.samato.paymentservice.domain.event.PaymentCaptured;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Payment aggregate.
 *
 *   - State is reconstructed by replaying events (see {@link #rehydrate}).
 *   - State changes go through {@link #apply(PaymentEvent)}, which is
 *     the ONLY mutator on the class. There are no setters.
 *   - Commands call a public decide method (e.g. {@link #createOrder},
 *     {@link #refund}) which validates and produces events.
 *   - Events are queued in {@link #uncommitted} and drained by the
 *     repository when persisting.
 *
 * Why no setters?
 *   In an event-sourced system, the aggregate's state is *derived* from
 *   events. A setter would let callers mutate state without an event,
 *   which would break the audit log. By making the only mutator
 *   package-private (and the events immutable records), the compiler
 *   enforces that every state change has a corresponding event.
 *
 * Note on field mutability:
 *   The identity fields (paymentId, orderId, customerId, currency) are
 *   declared non-final so the very first event (RazorpayOrderCreated)
 *   can populate them. After that first event, they are NEVER
 *   reassigned — a fact that is enforced by the {@link #apply} method
 *   (only the first event touches identity). We use a guard in apply
 *   to assert this.
 */
public class Payment {

    // --- identity (set once, by the first event) ----------------------
    private UUID paymentId;
    private UUID orderId;
    private UUID customerId;
    private String currency;

    // --- mutable state (changes only via apply) -----------------------
    private Money amount;
    private PaymentStatus status = PaymentStatus.ORDER_CREATED;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String lastRazorpayRefundId;
    private int version;

    // --- event sourcing plumbing --------------------------------------
    private final List<PaymentEvent> uncommitted = new ArrayList<>();

    /**
     * Replay constructor. Used by the repository to reconstruct state
     * from a stored event stream. The events have already happened;
     * they're not uncommitted.
     */
    public static Payment rehydrate(List<PaymentEvent> events) {
        Payment p = new Payment();
        for (PaymentEvent e : events) {
            p.apply(e);
        }
        p.uncommitted.clear();
        return p;
    }

    /**
     * Hydrate from a snapshot. The events AFTER the snapshot are
     * replayed on top of this state by the repository.
     */
    public static Payment rehydrateFromSnapshot(Snapshotter.SnapshotState s) {
        Payment p = new Payment();
        p.paymentId          = s.paymentId();
        p.orderId            = s.orderId();
        p.customerId         = s.customerId();
        p.currency           = s.currency();
        p.amount             = s.money();
        p.status             = s.status();
        p.razorpayOrderId    = s.razorpayOrderId();
        p.razorpayPaymentId  = s.razorpayPaymentId();
        p.version            = s.version();
        // uncommitted is empty — these events already happened.
        return p;
    }

    /** Test-only no-arg constructor. Don't use in production code. */
    Payment() {}

    // -- command entry points (the *decide* phase) ---------------------

    public RazorpayOrderCreated createOrder(PaymentCommand.CreateRazorpayOrder cmd,
                                            String razorpayOrderId,
                                            int nextVersion) {
        if (razorpayOrderId == null || razorpayOrderId.isBlank()) {
            throw new IllegalArgumentException("razorpayOrderId is required");
        }
        if (!"INR".equals(cmd.currency())) {
            throw new IllegalStateException("Currency must be INR; got " + cmd.currency());
        }
        RazorpayOrderCreated event = new RazorpayOrderCreated(
                cmd.paymentId(), cmd.orderId(), cmd.customerId(),
                razorpayOrderId, cmd.amount(), cmd.currency(), nextVersion);
        apply(event);
        return event;
    }

    public PaymentInitiated markPaymentInitiated(PaymentCommand.InitiatePayment cmd, int nextVersion) {
        if (status != PaymentStatus.ORDER_CREATED) {
            throw new IllegalStateException("Payment not in ORDER_CREATED: " + status);
        }
        PaymentInitiated event = new PaymentInitiated(
                cmd.paymentId(), razorpayOrderId, cmd.razorpayPaymentId(), nextVersion);
        apply(event);
        return event;
    }

    public PaymentCaptured markCaptured(String razorpayPaymentId, int nextVersion) {
        if (status == PaymentStatus.CAPTURED || status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Payment already captured/refunded: " + status);
        }
        PaymentCaptured event = new PaymentCaptured(
                paymentId, razorpayOrderId, razorpayPaymentId, amount, nextVersion);
        apply(event);
        return event;
    }

    public PaymentFailed markFailed(String razorpayPaymentId, String code, String desc, int nextVersion) {
        if (status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Cannot fail a refunded payment");
        }
        PaymentFailed event = new PaymentFailed(
                paymentId, razorpayOrderId, razorpayPaymentId, code, desc, nextVersion);
        apply(event);
        return event;
    }

    public RefundInitiated initiateRefund(PaymentCommand.RefundPayment cmd,
                                          String razorpayRefundId,
                                          int nextVersion) {
        if (status != PaymentStatus.CAPTURED) {
            throw new IllegalStateException("Cannot refund non-captured payment: " + status);
        }
        if (!cmd.amount().currency().equals(currency)) {
            throw new IllegalStateException("Refund currency mismatch: " + cmd.amount().currency());
        }
        RefundInitiated event = new RefundInitiated(
                paymentId, razorpayOrderId, razorpayPaymentId,
                razorpayRefundId, cmd.amount(), nextVersion);
        apply(event);
        return event;
    }

    public RefundCompleted completeRefund(String razorpayRefundId, Money refundAmount, int nextVersion) {
        if (status != PaymentStatus.REFUND_INITIATED) {
            throw new IllegalStateException("Cannot complete refund in state: " + status);
        }
        if (!razorpayRefundId.equals(lastRazorpayRefundId)) {
            throw new IllegalStateException("Refund id mismatch: " + razorpayRefundId
                    + " vs " + lastRazorpayRefundId);
        }
        RefundCompleted event = new RefundCompleted(paymentId, razorpayRefundId, refundAmount, nextVersion);
        apply(event);
        return event;
    }

    public PaymentExpired markExpired(int nextVersion) {
        if (status == PaymentStatus.CAPTURED || status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Cannot expire captured/refunded payment");
        }
        PaymentExpired event = new PaymentExpired(paymentId, razorpayOrderId, nextVersion);
        apply(event);
        return event;
    }

    // -- the only mutator ---------------------------------------------

    /**
     * The ONLY way state changes. The compiler enforces this because
     * all fields are private and there are no setters.
     */
    void apply(PaymentEvent e) {
        switch (e) {
            case RazorpayOrderCreated c -> {
                if (this.paymentId == null) {
                    // First event — establishes identity.
                    this.paymentId  = c.paymentId();
                    this.orderId    = c.orderId();
                    this.customerId = c.customerId();
                    this.currency   = c.currency();
                } else {
                    // Subsequent RazorpayOrderCreated would be a bug.
                    throw new IllegalStateException(
                            "RazorpayOrderCreated is only valid as the first event");
                }
                this.amount          = c.amount();
                this.status          = PaymentStatus.ORDER_CREATED;
                this.razorpayOrderId = c.razorpayOrderId();
            }
            case PaymentInitiated i -> {
                this.status           = PaymentStatus.PAYMENT_INITIATED;
                this.razorpayPaymentId = i.razorpayPaymentId();
            }
            case PaymentCaptured cap -> {
                this.status           = PaymentStatus.CAPTURED;
                this.razorpayPaymentId = cap.razorpayPaymentId();
            }
            case PaymentFailed f -> {
                this.status           = PaymentStatus.FAILED;
                this.razorpayPaymentId = f.razorpayPaymentId();
            }
            case RefundInitiated r -> {
                this.status               = PaymentStatus.REFUND_INITIATED;
                this.lastRazorpayRefundId = r.razorpayRefundId();
            }
            case RefundCompleted c -> {
                this.status = PaymentStatus.REFUNDED;
            }
            case PaymentExpired ex -> {
                this.status = PaymentStatus.EXPIRED;
            }
        }
        this.version = e.version();
        this.uncommitted.add(e);
    }

    // -- accessors -----------------------------------------------------

    public UUID getPaymentId()            { return paymentId; }
    public UUID getOrderId()              { return orderId; }
    public UUID getCustomerId()           { return customerId; }
    public String getCurrency()           { return currency; }
    public Money getAmount()              { return amount; }
    public PaymentStatus getStatus()      { return status; }
    public String getRazorpayOrderId()    { return razorpayOrderId; }
    public String getRazorpayPaymentId()  { return razorpayPaymentId; }
    public int getVersion()               { return version; }

    /** Snapshot of the uncommitted events. The repository drains them after persisting. */
    public List<PaymentEvent> drainUncommitted() {
        List<PaymentEvent> copy = List.copyOf(uncommitted);
        uncommitted.clear();
        return copy;
    }
}
