package com.example.transfers;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfer_mutex")
public class TransferMutex {
    @Id
    private Long id;

    protected TransferMutex() {}
}
