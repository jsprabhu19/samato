package com.samato.paymentservice.eventstore.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentSnapshotRepository extends JpaRepository<PaymentSnapshot, UUID> {
}
