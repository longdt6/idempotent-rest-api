package com.example.transfers;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotNull @Positive Long fromAccountId,
        @NotNull @Positive Long toAccountId,
        @NotNull @Positive @Max(1000000000000L) Long amountCents) {}
