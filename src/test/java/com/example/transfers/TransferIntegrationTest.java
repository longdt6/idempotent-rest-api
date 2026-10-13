package com.example.transfers;

import java.util.concurrent.*;
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
        assertThat(service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000001"), new TransferRequest(1L, 2L, 2000L)).status()).isEqualTo(409);
        assertThat(balance(1)).isEqualTo(99000);
        assertThat(balance(2)).isEqualTo(101000);
    }
    @Test void concurrentDuplicatesDebitOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Future<TransferService.Outcome>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> {
                start.await();
                return service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000002"), new TransferRequest(1L, 2L, 1000L));
            }));
            start.countDown();
            var id = tasks.getFirst().get(20, TimeUnit.SECONDS).body().transferId();
            for (var task : tasks) assertThat(task.get(20, TimeUnit.SECONDS).body().transferId()).isEqualTo(id);
        }
        assertThat(balance(1)).isEqualTo(99000);
        assertThat(balance(2)).isEqualTo(101000);
    }
    @Test void concurrentDistinctKeysCannotOverdraw() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000003"), new TransferRequest(1L, 2L, 80000L)));
            var b = pool.submit(() -> service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000004"), new TransferRequest(1L, 2L, 80000L)));
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS).status(), b.get(20, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(201, 422);
        }
        assertThat(balance(1)).isEqualTo(20000);
        assertThat(balance(2)).isEqualTo(180000);
    }
    @Test void oppositeDirectionTransfersCompleteWithoutDeadlock() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var forward = pool.submit(() -> {
                start.await();
                return service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000005"), new TransferRequest(1L, 2L, 50000L));
            });
            var reverse = pool.submit(() -> {
                start.await();
                return service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000006"), new TransferRequest(2L, 1L, 50000L));
            });
            start.countDown();
            assertThat(forward.get(20, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(reverse.get(20, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        assertThat(balance(1)).isEqualTo(100000);
        assertThat(balance(2)).isEqualTo(100000);
    }
    @Test void concurrentDifferentPayloadsUnderSameKeyAllowOnlyOneTransfer() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000007"), new TransferRequest(1L, 2L, 1000L));
            });
            var second = pool.submit(() -> {
                start.await();
                return service.transfer(UUID.fromString("00000000-0000-4000-8000-000000000007"), new TransferRequest(1L, 2L, 2000L));
            });
            start.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.status(), b.status())).containsExactlyInAnyOrder(201, 409);
            long transferred = a.status() == 201 ? a.body().amountCents() : b.body().amountCents();
            assertThat(balance(1)).isEqualTo(100000 - transferred);
            assertThat(balance(2)).isEqualTo(100000 + transferred);
        }
        assertThat(transfers.count()).isEqualTo(1);
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
