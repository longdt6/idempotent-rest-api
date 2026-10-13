-- Concurrency handling is outside the sequential idempotency demo.
-- Keep V2 unchanged so existing databases retain valid Flyway history.
DROP TABLE transfer_mutex;
