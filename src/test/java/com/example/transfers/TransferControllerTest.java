package com.example.transfers;

import java.util.UUID;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TransferControllerTest {
    private final TransferRequest request = new TransferRequest(1L, 2L, 2500L);

    @Test void rejectsMissingAndInvalidKeysBeforeCallingService() {
        var service = new RecordingService();
        var controller = new TransferController(service);
        for (String key : new String[]{null, "", " ", "key with spaces", "x".repeat(129)}) {
            assertThat(controller.transfer(key, request).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(service.calls).isZero();
    }

    @Test void replayPreservesStatusBodyAndLocation() {
        var service = new RecordingService();
        var body = new TransferResult(UUID.randomUUID(), 1L, 2L, 2500L, null);
        service.outcome = new TransferService.Outcome(201, body, true);
        var response = new TransferController(service).transfer("payment-001", request);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(body);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/transfers/" + body.transferId());
        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test void failedTransferHasNoLocation() {
        var service = new RecordingService();
        service.outcome = new TransferService.Outcome(422,
                new TransferResult(null, 1L, 2L, 2500L, "insufficient_funds"), true);
        var response = new TransferController(service).transfer("payment-001", request);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().getLocation()).isNull();
    }

    @Test void validatesMoneyAndRequiredAccountIds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request)).isEmpty();
            for (var invalid : new TransferRequest[]{
                    new TransferRequest(null, 2L, 2500L), new TransferRequest(0L, 2L, 2500L),
                    new TransferRequest(1L, null, 2500L), new TransferRequest(1L, -2L, 2500L),
                    new TransferRequest(1L, 2L, null), new TransferRequest(1L, 2L, 0L),
                    new TransferRequest(1L, 2L, -1L), new TransferRequest(1L, 2L, 1000000000001L)}) {
                assertThat(validator.validate(invalid)).isNotEmpty();
            }
        }
    }

    private static class RecordingService extends TransferService {
        int calls;
        Outcome outcome;
        RecordingService() { super(null, null, null); }
        @Override public Outcome transfer(String key, TransferRequest request) {
            calls++;
            return outcome;
        }
    }
}
