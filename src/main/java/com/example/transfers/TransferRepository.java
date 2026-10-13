package com.example.transfers;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface TransferRepository extends JpaRepository<TransferRecord, String> {
    Optional<TransferRecord> findByTransferId(UUID transferId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TransferRecord> findOneByIdempotencyKey(String key);
}
