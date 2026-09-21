-- M7: Recovery Control Plane Discovery Migration
-- Adds operational discovery metadata to existing M6 checkpoint schema.
--
-- IMPORTANT: This migration is SAFE for existing deployments:
-- - Adds nullable/defaulted column (no data loss)
-- - Adds index (performance only, no semantic change)
-- - Does NOT change checkpoint authority semantics
-- - Does NOT require checkpoint data migration

-- Add updated_at timestamp for discovery ordering
-- Default to CURRENT_TIMESTAMP so existing rows get a reasonable value
ALTER TABLE arctra_checkpoints ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Backfill existing rows with current timestamp if database doesn't auto-populate
-- (Some databases may need explicit UPDATE for existing rows)
-- UPDATE arctra_checkpoints SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL;

-- Create index for efficient discovery queries ordered by update time
CREATE INDEX idx_checkpoints_updated ON arctra_checkpoints(updated_at);

-- Verification query: Check migration success
-- SELECT process_id, checkpoint_version, updated_at FROM arctra_checkpoints ORDER BY updated_at;
