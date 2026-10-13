ALTER TABLE transfer_requests
    ADD COLUMN processing_state VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN request_method VARCHAR(10) NOT NULL DEFAULT 'POST',
    ADD COLUMN request_path VARCHAR(100) NOT NULL DEFAULT '/transfers',
    ADD COLUMN owner_token UUID,
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE transfer_requests SET processing_started_at = created_at;
ALTER TABLE transfer_requests ALTER COLUMN processing_started_at SET NOT NULL;
ALTER TABLE transfer_requests ADD CONSTRAINT transfer_processing_state
    CHECK (processing_state IN ('PROCESSING', 'COMPLETED'));
