package com.example.transfers;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.transfers.TransferService.Outcome;

@Service
public class TransferProcessor {
    private static final long MAX_BALANCE_CENTS = 1000000000000L;
    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public TransferProcessor(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    @Transactional
    public Outcome process(UUID key, TransferRequest request, UUID token) {
        // Only lock this request row, never a shared mutex or all transfers.
        var stored = transfers.findOneByIdempotencyKey(key.toString()).orElseThrow();
        if (stored.isCompleted()) return new Outcome(stored.getHttpStatus(), stored.toResult(), true);
        if (!stored.isOwnedBy(token)) return TransferService.processing(request);
        if (request.fromAccountId().equals(request.toAccountId())) {
            return finish(stored, 422, null, "same_account");
        }
        var sourceAccount = accounts.findById(request.fromAccountId());
        var destinationAccount = accounts.findById(request.toAccountId());
        if (sourceAccount.isEmpty() || destinationAccount.isEmpty()) {
            return finish(stored, 404, null, "account_not_found");
        }
        var source = sourceAccount.get();
        var destination = destinationAccount.get();
        if (source.getBalanceCents() < request.amountCents()) {
            return finish(stored, 422, null, "insufficient_funds");
        }
        if (destination.getBalanceCents() > MAX_BALANCE_CENTS - request.amountCents()) {
            return finish(stored, 422, null, "destination_balance_limit");
        }
        // Hibernate dirty checking persists both managed accounts at transaction commit.
        source.debit(request.amountCents());
        destination.credit(request.amountCents());
        return finish(stored, 201, UUID.randomUUID(), null);
    }

    private Outcome finish(TransferRecord stored, int status, UUID id, String error) {
        // Balance changes and the completed response commit in the same transaction.
        stored.complete(status, id, error);
        return new Outcome(status, stored.toResult(), false);
    }
}
