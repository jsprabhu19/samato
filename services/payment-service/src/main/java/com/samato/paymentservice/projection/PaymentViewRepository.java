package com.samato.paymentservice.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentViewRepository extends JpaRepository<PaymentView, UUID> {

    Optional<PaymentView> findByRazorpayOrderId(String razorpayOrderId);

    Optional<PaymentView> findByOrderId(UUID orderId);
}
