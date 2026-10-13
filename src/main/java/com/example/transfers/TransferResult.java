package com.example.transfers;

import java.util.UUID;

public record TransferResult(UUID transferId, Long fromAccountId, Long toAccountId,
                             Long amountCents, String error) {}
