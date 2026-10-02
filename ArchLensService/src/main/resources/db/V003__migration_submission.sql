-- A host-generated submission key binds one immutable request to one new MigrationRun.
-- Reusing the key with different content is a conflict; retries read the original run.
CREATE TABLE archlens.migration_submission (
    submission_key text PRIMARY KEY CHECK (submission_key ~ '^[A-Za-z0-9._:-]{1,200}$'),
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    run_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id, request_hash) REFERENCES archlens.migration_run(run_id, request_hash)
);
