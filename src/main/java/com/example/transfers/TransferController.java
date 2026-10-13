package com.example.transfers;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
public class TransferController {
    private final TransferService service;
    public TransferController(TransferService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    public List<TransferService.AccountBalance> accounts() {
        return service.listAccounts();
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResult> transfer(@RequestHeader("Idempotency-Key") UUID key,
                                      @Valid @RequestBody TransferRequest request) {
        var outcome = service.transfer(key, request);
        var response = ResponseEntity.status(outcome.status())
                .header("Idempotency-Replayed", Boolean.toString(outcome.replayed()));
        if (outcome.status() == 409) response.header("Retry-After", "1");
        if (outcome.body().transferId() != null) {
            response.location(URI.create("/transfers/" + outcome.body().transferId()));
        }
        return response.body(outcome.body());
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<TransferResult> get(@PathVariable UUID id) {
        return service.findTransfer(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
