package com.example.transfers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private static final long MAX_BALANCE_CENTS = 1000000000000L;
    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public TransferService(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
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

    @Transactional
    public Outcome transfer(UUID key, TransferRequest request) {
        // This learning demo assumes requests execute sequentially.
        var existing = transfers.findById(key.toString());
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
        var sourceAccount = accounts.findById(request.fromAccountId());
        var destinationAccount = accounts.findById(request.toAccountId());
        if (sourceAccount.isEmpty() || destinationAccount.isEmpty()) {
            return finish(key, request, 404, null, "account_not_found");
        }
        var source = sourceAccount.get();
        var destination = destinationAccount.get();
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

    private Outcome finish(UUID key, TransferRequest request, int status, UUID id, String error) {
        var stored = transfers.save(new TransferRecord(key, request, status, id, error));
        return new Outcome(status, stored.toResult(), false);
    }
}
