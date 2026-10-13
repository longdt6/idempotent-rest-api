package com.example.transfers;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Version;
import jakarta.persistence.Transient;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "transfer_requests")
public class TransferRecord implements Persistable<String> {
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

    public enum State { PROCESSING, COMPLETED }

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 20)
    private State state;

    @Column(name = "request_method", nullable = false, length = 10)
    private String requestMethod;

    @Column(name = "request_path", nullable = false, length = 100)
    private String requestPath;

    @Column(name = "owner_token")
    private UUID ownerToken;

    @Column(name = "processing_started_at", nullable = false)
    private Instant processingStartedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Transient
    private boolean newRecord = true;

    protected TransferRecord() {}

    public TransferRecord(UUID key, TransferRequest request, UUID ownerToken, Instant now) {
        this.idempotencyKey = key.toString();
        this.fromAccountId = request.fromAccountId();
        this.toAccountId = request.toAccountId();
        this.amountCents = request.amountCents();
        this.requestMethod = "POST";
        this.requestPath = "/transfers";
        this.createdAt = now;
        this.state = State.PROCESSING;
        this.ownerToken = ownerToken;
        this.processingStartedAt = now;
    }

    // Assigned IDs must use INSERT, not merge(), when claiming a new key.
    @Override public String getId() { return idempotencyKey; }
    @Override public boolean isNew() { return newRecord; }
    @PostLoad @PostPersist
    private void markPersisted() { newRecord = false; }

    public boolean isCompleted() { return state == State.COMPLETED; }
    public boolean isOwnedBy(UUID token) { return token.equals(ownerToken); }
    public boolean isExpired(Instant now) {
        return !isCompleted() && processingStartedAt.plus(Duration.ofSeconds(30)).isBefore(now);
    }
    public void takeOver(UUID token, Instant now) {
        ownerToken = token;
        processingStartedAt = now;
    }
    public void complete(int status, UUID transferId, String error) {
        this.httpStatus = status;
        this.transferId = transferId;
        this.error = error;
        this.state = State.COMPLETED;
    }

    public int getHttpStatus() { return httpStatus; }

    public boolean matches(TransferRequest request) {
        return "POST".equals(requestMethod) && "/transfers".equals(requestPath)
                && fromAccountId.equals(request.fromAccountId())
                && toAccountId.equals(request.toAccountId())
                && amountCents.equals(request.amountCents());
    }

    public TransferResult toResult() {
        return new TransferResult(transferId, fromAccountId, toAccountId, amountCents, error);
    }
}
