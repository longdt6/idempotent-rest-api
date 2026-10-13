package com.example.transfers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private static final long MAX_BALANCE_CENTS = 1000000000000L;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final TransferMutexRepository mutex;

    public TransferService(AccountRepository accounts, TransferRepository transfers, TransferMutexRepository mutex) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.mutex = mutex;
    }

    public record Outcome(int status, TransferResult body, boolean replayed) {}
    public record AccountBalance(Long id, long balanceCents) {}

    @Transactional(readOnly = true)
    public List<AccountBalance> listAccounts() {
        return accounts.findAllByOrderByIdAsc().stream()
                .map(account -> new AccountBalance(account.getId(), account.getBalanceCents())).toList();
    }

    @Transactional(readOnly = true)
    public Optional<TransferResult> findTransfer(UUID id) {
        return transfers.findByTransferId(id).map(TransferRecord::toResult);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome transfer(String key, TransferRequest request) {
        // Lock an existing row before looking up a key: a missing request row cannot be locked.
        // This intentionally serializes all transfers for the minimal learning demo.
        mutex.findById(1L).orElseThrow(() -> new IllegalStateException("Transfer mutex is missing"));
        var existing = transfers.findById(key);
        if (existing.isPresent()) {
            var stored = existing.get();
            if (!stored.matches(request)) {
                return new Outcome(409, new TransferResult(null, request.fromAccountId(),
                        request.toAccountId(), request.amountCents(), "idempotency_key_reused"), false);
            }
            return new Outcome(stored.getHttpStatus(), stored.toResult(), true);
        }
        if (request.fromAccountId().equals(request.toAccountId())) {
            return finish(key, request, 422, null, "same_account");
        }
        // Use a consistent lock order, including opposite-direction transfers.
        long firstId = Math.min(request.fromAccountId(), request.toAccountId());
        long secondId = Math.max(request.fromAccountId(), request.toAccountId());
        var first = accounts.findOneById(firstId);
        var second = accounts.findOneById(secondId);
        if (first.isEmpty() || second.isEmpty()) {
            return finish(key, request, 404, null, "account_not_found");
        }
        Account source = request.fromAccountId() == firstId ? first.get() : second.get();
        Account destination = request.toAccountId() == firstId ? first.get() : second.get();
        if (source.getBalanceCents() < request.amountCents()) {
            return finish(key, request, 422, null, "insufficient_funds");
        }
        if (destination.getBalanceCents() > MAX_BALANCE_CENTS - request.amountCents()) {
            return finish(key, request, 422, null, "destination_balance_limit");
        }
        // Hibernate dirty checking persists both managed accounts at transaction commit.
        source.debit(request.amountCents());
        destination.credit(request.amountCents());
        return finish(key, request, 201, UUID.randomUUID(), null);
    }

    private Outcome finish(String key, TransferRequest request, int status, UUID id, String error) {
        var stored = transfers.save(new TransferRecord(key, request, status, id, error));
        return new Outcome(status, stored.toResult(), false);
    }
}
