package com.example.transfers;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfer_requests")
public class TransferRecord {
    @Id
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "from_account", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account", nullable = false)
    private Long toAccountId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "transfer_id", unique = true)
    private UUID transferId;

    @Column(length = 100)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferRecord() {}

    public TransferRecord(UUID key, TransferRequest request, int status, UUID transferId, String error) {
        this.idempotencyKey = key.toString();
        this.fromAccountId = request.fromAccountId();
        this.toAccountId = request.toAccountId();
        this.amountCents = request.amountCents();
        this.httpStatus = status;
        this.transferId = transferId;
        this.error = error;
        this.createdAt = Instant.now();
    }

    public int getHttpStatus() { return httpStatus; }

    public boolean matches(TransferRequest request) {
        return fromAccountId.equals(request.fromAccountId())
                && toAccountId.equals(request.toAccountId())
                && amountCents.equals(request.amountCents());
    }

    public TransferResult toResult() {
        return new TransferResult(transferId, fromAccountId, toAccountId, amountCents, error);
    }
}
