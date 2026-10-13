package com.example.transfers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private Long id;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    protected Account() {}

    public Account(Long id, long balanceCents) {
        this.id = id;
        this.balanceCents = balanceCents;
    }

    public Long getId() { return id; }
    public long getBalanceCents() { return balanceCents; }
    public void debit(long amountCents) { balanceCents -= amountCents; }
    public void credit(long amountCents) { balanceCents += amountCents; }
}
