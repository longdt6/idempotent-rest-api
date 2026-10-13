package com.example.transfers;

import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class TransferIntegrationTest {
    @LocalServerPort int port;
    @Autowired TransferService service;
    @Autowired AccountRepository accounts;
    @Autowired TransferRepository transfers;
    @Autowired TransferClaimService claims;
    @Autowired TransferProcessor processor;

    @BeforeEach void reset() {
        transfers.deleteAllInBatch();
        accounts.saveAll(java.util.List.of(new Account(1L, 100000), new Account(2L, 100000)));
    }
    long balance(long id) {
        return accounts.findById(id).orElseThrow().getBalanceCents();
    }
    @Test void retriesReplayAndRejectDifferentPayload() {
        var request = new TransferRequest(1L, 2L, 1000L);
        var first = service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000001"), request);
        var second = service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000001"), request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.replayed()).isTrue();
        assertThat(service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000001"), new TransferRequest(1L, 2L, 2000L)).status()).isEqualTo(422);
        assertThat(balance(1)).isEqualTo(99000);
        assertThat(balance(2)).isEqualTo(101000);
    }
    @Test void processingRequestReturns409AndDoesNotMoveMoney() {
        var key = UUID.randomUUID();
        var request = new TransferRequest(1L, 2L, 1000L);
        transfers.saveAndFlush(new TransferRecord(key, request, UUID.randomUUID(), java.time.Instant.now()));
        var result = service.transfer(key, request);
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body().error()).isEqualTo("request_processing");
        assertThat(balance(1)).isEqualTo(100000);
        assertThat(balance(2)).isEqualTo(100000);
        assertThat(transfers.count()).isEqualTo(1);
    }
    @Test void expiredClaimCanBeTakenOverWithoutAllowingTheFormerOwnerToTransfer() {
        var key = UUID.randomUUID();
        var oldOwner = UUID.randomUUID();
        var newOwner = UUID.randomUUID();
        var request = new TransferRequest(1L, 2L, 1000L);
        transfers.saveAndFlush(new TransferRecord(key, request, oldOwner,
                java.time.Instant.now().minusSeconds(31)));
        assertThat(claims.resolve(key, request, newOwner)).isNull();
        assertThat(processor.process(key, request, oldOwner).status()).isEqualTo(409);
        assertThat(processor.process(key, request, newOwner).status()).isEqualTo(201);
        assertThat(service.transfer(key, request).replayed()).isTrue();
        assertThat(balance(1)).isEqualTo(99000);
        assertThat(balance(2)).isEqualTo(101000);
    }
    @Test void failedWorkerReleasesOnlyItsOwnProcessingKey() {
        var key = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var request = new TransferRequest(1L, 2L, 1000L);
        claims.insert(key, request, owner);
        claims.release(key, UUID.randomUUID());
        assertThat(transfers.existsById(key.toString())).isTrue();
        claims.release(key, owner);
        assertThat(transfers.existsById(key.toString())).isFalse();
        assertThat(service.transfer(key, request).status()).isEqualTo(201);
    }
    @Test void newKeyCreatesAnotherTransferForTheSamePayload() {
        var request = new TransferRequest(1L, 2L, 1000L);
        var first = service.transfer(UUID.randomUUID(), request);
        var second = service.transfer(UUID.randomUUID(), request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.replayed()).isFalse();
        assertThat(second.body().transferId()).isNotEqualTo(first.body().transferId());
        assertThat(transfers.count()).isEqualTo(2);
        assertThat(balance(1)).isEqualTo(98000);
        assertThat(balance(2)).isEqualTo(102000);
    }
    @Test void failedTransferIsRememberedWithoutChangingBalances() {
        var request = new TransferRequest(1L, 2L, 200000L);
        var first = service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000008"), request);
        accounts.save(new Account(1L, 300000));
        var replay = service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000008"), request);
        assertThat(first.status()).isEqualTo(422);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.replayed()).isTrue();
        assertThat(balance(1)).isEqualTo(300000);
        assertThat(balance(2)).isEqualTo(100000);
    }
    @Test void httpRejectsFractionalCentsAndUnknownFieldsWithoutConsumingKey() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String body : new String[]{
                    "{\"fromAccountId\":1,\"toAccountId\":2,\"amountCents\":1.9}",
                    "{\"fromAccountId\":1,\"toAccountId\":2,\"amountCents\":100,\"currency\":\"EUR\"}"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transfers"))
                        .header("Content-Type", "application/json").header("Idempotency-Key", "00000000-0000-4000-8000-000000000009")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(400);
            }
        }
        assertThat(transfers.count()).isZero();
        assertThat(balance(1)).isEqualTo(100000);
    }

    @Test void httpRetryReplaysOriginalResponseAndLocation() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transfers"))
                    .header("Content-Type", "application/json").header("Idempotency-Key", "00000000-0000-4000-8000-000000000010")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"fromAccountId\":1,\"toAccountId\":2,\"amountCents\":2500}"))
                    .build();
            var first = client.send(request, HttpResponse.BodyHandlers.ofString());
            var replay = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(first.body());
            assertThat(first.headers().firstValue("Idempotency-Replayed")).contains("false");
            assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
            assertThat(replay.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
            var get = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + first.headers().firstValue("Location").orElseThrow())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(get.body()).isEqualTo(first.body());
        }
        assertThat(balance(1)).isEqualTo(97500);
        assertThat(balance(2)).isEqualTo(102500);
    }

}
