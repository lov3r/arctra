-- M6-T4E: JDBC Durable Recovery Store Pair Schema
-- M6-T5: Physical Attempt Identity & Recovery Resolution
-- M7: Recovery Control Plane Discovery
--
-- Target: PostgreSQL (production), H2 (tests)
--
-- Schema initialization is application/deployment responsibility.
-- This file is documentation and test fixture reference.

-- Checkpoint storage
-- M7: Added updated_at for operational discovery ordering
CREATE TABLE arctra_checkpoints (
    process_id          VARCHAR(255) PRIMARY KEY,
    checkpoint_version  BIGINT NOT NULL,
    schema_version      VARCHAR(32) NOT NULL,
    runtime_binding_key VARCHAR(255) NOT NULL,
    session_id          VARCHAR(255),
    checkpoint_data     TEXT NOT NULL,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- M7: Index for operational discovery queries ordered by update time
CREATE INDEX idx_checkpoints_updated ON arctra_checkpoints(updated_at);

-- M6-T5: Invocation intent storage with attempt identity
-- Migration from M6-T4: See migration notes below
CREATE TABLE arctra_invocation_intents (
    process_id   VARCHAR(255) NOT NULL,
    operation_id VARCHAR(255) NOT NULL,
    attempt_id   VARCHAR(255) NOT NULL,
    recorded_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (process_id, operation_id, attempt_id)
);

-- M6-T5: Recovery resolution storage
CREATE TABLE arctra_recovery_resolutions (
    process_id       VARCHAR(255) NOT NULL,
    operation_id     VARCHAR(255) NOT NULL,
    attempt_id       VARCHAR(255) NOT NULL,
    resolution_type  VARCHAR(50) NOT NULL,
    recovered_result TEXT,
    resolved_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (process_id, operation_id, attempt_id),
    FOREIGN KEY (process_id, operation_id, attempt_id)
        REFERENCES arctra_invocation_intents(process_id, operation_id, attempt_id)
);

-- Index for operation-level queries (all attempts for an operation)
CREATE INDEX idx_invocation_intents_operation
    ON arctra_invocation_intents(process_id, operation_id);

CREATE INDEX idx_recovery_resolutions_operation
    ON arctra_recovery_resolutions(process_id, operation_id);

-- =============================================================================
-- M6-T5 MIGRATION NOTES: Upgrading from M6-T4
-- =============================================================================
--
-- M6-T4 schema has PRIMARY KEY (process_id, operation_id)
-- M6-T5 schema requires PRIMARY KEY (process_id, operation_id, attempt_id)
--
-- Migration strategy:
--
-- 1. Add attempt_id column (nullable temporarily)
-- ALTER TABLE arctra_invocation_intents ADD COLUMN attempt_id VARCHAR(255);
--
-- 2. Populate legacy attempts with deterministic attemptId
-- UPDATE arctra_invocation_intents
-- SET attempt_id = CONCAT(operation_id, '#legacy')
-- WHERE attempt_id IS NULL;
--
-- 3. Make attempt_id NOT NULL
-- ALTER TABLE arctra_invocation_intents ALTER COLUMN attempt_id SET NOT NULL;
--
-- 4. Drop old PRIMARY KEY
-- ALTER TABLE arctra_invocation_intents DROP CONSTRAINT arctra_invocation_intents_pkey;
--
-- 5. Add new PRIMARY KEY
-- ALTER TABLE arctra_invocation_intents
-- ADD PRIMARY KEY (process_id, operation_id, attempt_id);
--
-- 6. Create recovery_resolutions table (as above)
--
-- 7. Create indexes (as above)
--
-- CRITICAL: Existing M6-T4 invocation intents remain MAY_HAVE_INVOKED after upgrade.
-- Legacy attempts can be resolved via RecoveryResolution API using attemptId="{operationId}#legacy"
