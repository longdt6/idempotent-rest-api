package com.example.transfers;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TransferMutexRepository extends JpaRepository<TransferMutex, Long> {
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TransferMutex> findById(Long id);
}
