package com.samato.paymentservice.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommand, UUID> {

    /**
     * Returns the prior processing record for this (command_type, key)
     * tuple, if any. Used to short-circuit a duplicate request.
     */
    Optional<ProcessedCommand> findByCommandTypeAndKey(String commandType, String key);
}
