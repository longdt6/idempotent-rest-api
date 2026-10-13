CREATE TABLE accounts (
    id BIGINT PRIMARY KEY,
    balance_cents BIGINT NOT NULL CHECK (balance_cents >= 0 AND balance_cents <= 1000000000000)
);
-- All accounts in this demo use USD; amounts are integer cents.
INSERT INTO accounts VALUES (1, 100000), (2, 50000);

CREATE TABLE transfer_requests (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    from_account BIGINT NOT NULL,
    to_account BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    http_status INTEGER,
    transfer_id UUID UNIQUE,
    error VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
