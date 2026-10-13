package com.example.transfers;

import java.util.UUID;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class TransferControllerTest {
    private final TransferRequest request = new TransferRequest(1L, 2L, 2500L);

    @Test void springRejectsMissingAndInvalidKeysBeforeCallingService() throws Exception {
        var service = new RecordingService();
        var mvc = MockMvcBuilders.standaloneSetup(new TransferController(service)).build();
        String body = "{\"fromAccountId\":1,\"toAccountId\":2,\"amountCents\":2500}";
        mvc.perform(post("/transfers").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        for (String key : new String[]{"", " ", "payment-001", "not-a-uuid", "x".repeat(129)}) {
            mvc.perform(post("/transfers").header("Idempotency-Key", key)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(service.calls).isZero();
    }

    @Test void springConvertsHeaderToUuid() throws Exception {
        var service = new RecordingService();
        var id = UUID.randomUUID();
        service.outcome = new TransferService.Outcome(201,
                new TransferResult(UUID.randomUUID(), 1L, 2L, 2500L, null), false);
        var mvc = MockMvcBuilders.standaloneSetup(new TransferController(service)).build();
        mvc.perform(post("/transfers").header("Idempotency-Key", id.toString())
                .contentType("application/json")
                .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amountCents\":2500}"))
                .andExpect(status().isCreated());
        assertThat(service.key).isEqualTo(id);
        assertThat(service.calls).isEqualTo(1);
    }

    @Test void replayPreservesStatusBodyAndLocation() {
        var service = new RecordingService();
        var body = new TransferResult(UUID.randomUUID(), 1L, 2L, 2500L, null);
        service.outcome = new TransferService.Outcome(201, body, true);
        var response = new TransferController(service).transfer(UUID.randomUUID(), request);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(body);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/transfers/" + body.transferId());
        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test void failedTransferHasNoLocation() {
        var service = new RecordingService();
        service.outcome = new TransferService.Outcome(422,
                new TransferResult(null, 1L, 2L, 2500L, "insufficient_funds"), true);
        var response = new TransferController(service).transfer(UUID.randomUUID(), request);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().getLocation()).isNull();
    }

    @Test void processingResponseTellsClientToRetryLater() throws Exception {
        var service = new RecordingService();
        service.outcome = TransferService.processing(request);
        var mvc = MockMvcBuilders.standaloneSetup(new TransferController(service)).build();
        mvc.perform(post("/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amountCents\":2500}"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "1"));
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
        UUID key;
        Outcome outcome;
        RecordingService() { super(null, null, null, null, 0); }
        @Override public Outcome transfer(UUID key, TransferRequest request) {
            calls++;
            this.key = key;
            return outcome;
        }
    }
}
