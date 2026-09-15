-- M6-T4E: JDBC Durable Recovery Store Pair Schema
--
-- Target: PostgreSQL (production), H2 (tests)
--
-- Schema initialization is application/deployment responsibility.
-- This file is documentation and test fixture reference.

-- Checkpoint storage
CREATE TABLE arctra_checkpoints (
    process_id          VARCHAR(255) PRIMARY KEY,
    checkpoint_version  BIGINT NOT NULL,
    schema_version      VARCHAR(32) NOT NULL,
    runtime_binding_key VARCHAR(255) NOT NULL,
    session_id          VARCHAR(255),
    checkpoint_data     TEXT NOT NULL
);

-- Invocation intent storage
CREATE TABLE arctra_invocation_intents (
    process_id   VARCHAR(255) NOT NULL,
    operation_id VARCHAR(255) NOT NULL,
    recorded_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (process_id, operation_id)
);
