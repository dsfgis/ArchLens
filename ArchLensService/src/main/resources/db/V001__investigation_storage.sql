CREATE SCHEMA IF NOT EXISTS archlens;
CREATE TABLE IF NOT EXISTS archlens.schema_version (
    version integer PRIMARY KEY, checksum text NOT NULL, installed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS archlens.investigation_case (
    case_id uuid PRIMARY KEY, latest_revision integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS archlens.investigation_run (
    run_id uuid PRIMARY KEY, case_id uuid NOT NULL REFERENCES archlens.investigation_case(case_id),
    revision integer NOT NULL CHECK (revision > 0),
    state text NOT NULL CHECK (state IN ('RUNNING','PARTIAL','COMPLETED','CANCELLED','FAILED')),
    epoch bigint NOT NULL DEFAULT 1, lease_until timestamptz NOT NULL,
    request_hash text NOT NULL, request_json jsonb NOT NULL,
    report_hash text, report_json jsonb, graph_hash text, graph_json jsonb,
    checkpoint_hash text, checkpoint_json jsonb,
    projection_state text NOT NULL DEFAULT 'NOT_READY' CHECK (projection_state IN ('NOT_READY','PENDING','READY','NOT_APPLICABLE')),
    error_code text, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
    UNIQUE(case_id, revision),
    CHECK ((report_hash IS NULL) = (report_json IS NULL)),
    CHECK ((graph_hash IS NULL) = (graph_json IS NULL)),
    CHECK ((checkpoint_hash IS NULL) = (checkpoint_json IS NULL))
);
CREATE INDEX IF NOT EXISTS investigation_run_pending ON archlens.investigation_run(projection_state);
