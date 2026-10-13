package com.example.transfers;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
