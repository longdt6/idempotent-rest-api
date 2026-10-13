package com.example.transfers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransferClaimServiceTest {
    private final UUID key = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final TransferRequest request = new TransferRequest(1L, 2L, 2500L);
    private final TransferRepository repository = mock(TransferRepository.class);
    private final TransferClaimService claims = new TransferClaimService(repository);

    private TransferRecord stored(Instant startedAt) {
        var record = new TransferRecord(key, request, owner, startedAt);
        when(repository.findById(key.toString())).thenReturn(Optional.of(record));
        return record;
    }

    @Test void activeRequestReturns409() {
        stored(Instant.now());
        var response = claims.resolve(key, request, UUID.randomUUID());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().error()).isEqualTo("request_processing");
    }

    @Test void differentPayloadReturns422EvenWhileProcessing() {
        stored(Instant.now());
        var response = claims.resolve(key, new TransferRequest(1L, 2L, 3000L), UUID.randomUUID());
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().error()).isEqualTo("idempotency_key_reused");
    }

    @Test void completedRequestReplaysOriginalResponse() {
        var record = stored(Instant.now());
        record.complete(201, UUID.randomUUID(), null);
        var response = claims.resolve(key, request, UUID.randomUUID());
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(record.toResult());
        assertThat(response.replayed()).isTrue();
    }

    @Test void expiredRequestTransfersOwnership() {
        var record = stored(Instant.now().minusSeconds(31));
        var newOwner = UUID.randomUUID();
        assertThat(claims.resolve(key, request, newOwner)).isNull();
        assertThat(record.isOwnedBy(newOwner)).isTrue();
        assertThat(record.isOwnedBy(owner)).isFalse();
        assertThat(record.isExpired(Instant.now())).isFalse();
        verify(repository).flush();
    }

    @Test void formerOwnerCannotExecuteAfterTakeover() {
        var record = stored(Instant.now().minusSeconds(31));
        record.takeOver(UUID.randomUUID(), Instant.now());
        when(repository.findOneByIdempotencyKey(key.toString())).thenReturn(Optional.of(record));
        var accounts = mock(AccountRepository.class);
        var result = new TransferProcessor(accounts, repository).process(key, request, owner);
        assertThat(result.status()).isEqualTo(409);
        verifyNoInteractions(accounts);
    }

    @Test void formerOwnerCannotDeleteAnotherOwnersClaim() {
        var record = stored(Instant.now());
        record.takeOver(UUID.randomUUID(), Instant.now());
        when(repository.findOneByIdempotencyKey(key.toString())).thenReturn(Optional.of(record));
        claims.release(key, owner);
        verify(repository, never()).delete(any());
    }
}
