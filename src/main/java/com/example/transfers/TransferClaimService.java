package com.example.transfers;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferClaimService {
    private final TransferRepository transfers;
    public TransferClaimService(TransferRepository transfers) { this.transfers = transfers; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(UUID key, TransferRequest request, UUID token) {
        // Commit PROCESSING before business logic, so retries can see it.
        transfers.saveAndFlush(new TransferRecord(key, request, token, Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferService.Outcome resolve(UUID key, TransferRequest request, UUID token) {
        var existing = transfers.findById(key.toString());
        // An unsuccessful worker may have removed the record between these transactions.
        if (existing.isEmpty()) return TransferService.processing(request);
        var stored = existing.get();
        if (!stored.matches(request)) {
            return new TransferService.Outcome(422, new TransferResult(null, request.fromAccountId(),
                    request.toAccountId(), request.amountCents(), "idempotency_key_reused"), false);
        }
        if (stored.isCompleted()) {
            return new TransferService.Outcome(stored.getHttpStatus(), stored.toResult(), true);
        }
        var now = Instant.now();
        if (!stored.isExpired(now)) return TransferService.processing(request);
        // @Version makes competing takeovers fail rather than both claim ownership.
        stored.takeOver(token, now);
        transfers.flush();
        return null; // This request owns the expired claim and may process it.
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID key, UUID token) {
        transfers.findOneByIdempotencyKey(key.toString())
                .filter(record -> !record.isCompleted() && record.isOwnedBy(token))
                .ifPresent(transfers::delete);
    }
}
