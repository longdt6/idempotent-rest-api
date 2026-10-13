package com.example.transfers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final TransferClaimService claims;
    private final TransferProcessor processor;
    private final long processingDelayMs;

    public TransferService(AccountRepository accounts, TransferRepository transfers,
                           TransferClaimService claims, TransferProcessor processor,
                           @Value("${demo.processing-delay-ms:0}") long processingDelayMs) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.claims = claims;
        this.processor = processor;
        this.processingDelayMs = processingDelayMs;
    }

    public record Outcome(int status, TransferResult body, boolean replayed) {
    }

    public record AccountBalance(Long id, long balanceCents) {
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> listAccounts() {
        return accounts.findAllByOrderByIdAsc().stream()
                .map(account -> new AccountBalance(account.getId(), account.getBalanceCents())).toList();
    }

    @Transactional(readOnly = true)
    public Optional<TransferResult> findTransfer(UUID id) {
        return transfers.findByTransferId(id).map(TransferRecord::toResult);
    }

    public Outcome transfer(UUID key, TransferRequest request) {
        UUID token = UUID.randomUUID();
        try {
            claims.insert(key, request, token);
        } catch (DataIntegrityViolationException duplicate) {
            // The failed INSERT transaction has rolled back before inspecting the existing key.
            try {
                var response = claims.resolve(key, request, token);
                if (response != null) return response;
            } catch (ObjectOptimisticLockingFailureException takeoverLost) {
                return processing(request);
            }
        }
        try {
            // Optional demo delay occurs before the transaction that moves money.
            if (processingDelayMs > 0) Thread.sleep(processingDelayMs);
            return processor.process(key, request, token);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            claims.release(key, token);
            throw new IllegalStateException("Transfer interrupted", interrupted);
        } catch (RuntimeException failure) {
            try {
                claims.release(key, token);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static Outcome processing(TransferRequest request) {
        return new Outcome(409, new TransferResult(null, request.fromAccountId(),
                request.toAccountId(), request.amountCents(), "request_processing"), false);
    }
}
