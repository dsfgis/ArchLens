-- Migration Agent business state is separate from V001 investigation state.
-- LangGraph checkpoint storage has its own schema and will get package-specific tables later.
CREATE SCHEMA archlens_checkpoint;
REVOKE ALL ON SCHEMA archlens_checkpoint FROM PUBLIC;

CREATE TABLE archlens.migration_case (
    case_id uuid PRIMARY KEY,
    latest_revision integer NOT NULL DEFAULT 0 CHECK (latest_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE archlens.migration_run (
    run_id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES archlens.migration_case(case_id),
    revision integer NOT NULL CHECK (revision > 0),
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    request_json jsonb NOT NULL CHECK (request_json->>'schemaVersion' = 'archlens.migration-request.v1'),
    orchestration_type text NOT NULL CHECK (orchestration_type IN ('LEGACY','LANGGRAPH')),
    execution_state text NOT NULL CHECK (execution_state IN
        ('QUEUED','RUNNING','NEEDS_INPUT','WAITING_EXTERNAL','COMPLETED','PARTIAL','FAILED','CANCELLED','SUPERSEDED')),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    worker_id text,
    lease_until timestamptz,
    snapshot_hash text CHECK (snapshot_hash IS NULL OR snapshot_hash ~ '^[0-9a-f]{64}$'),
    plan_hash text CHECK (plan_hash IS NULL OR plan_hash ~ '^[0-9a-f]{64}$'),
    receipt_hash text CHECK (receipt_hash IS NULL OR receipt_hash ~ '^[0-9a-f]{64}$'),
    receipt_json jsonb,
    legacy_run_ref uuid REFERENCES archlens.investigation_run(run_id),
    source_report_hash text CHECK (source_report_hash IS NULL OR source_report_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    UNIQUE(case_id, revision),
    UNIQUE(run_id, revision),
    UNIQUE(run_id, request_hash),
    CHECK ((worker_id IS NULL) = (lease_until IS NULL)),
    CHECK ((execution_state = 'RUNNING') = (worker_id IS NOT NULL)),
    CHECK ((receipt_hash IS NULL) = (receipt_json IS NULL)),
    CHECK ((legacy_run_ref IS NULL) = (source_report_hash IS NULL))
);
CREATE INDEX migration_run_claim ON archlens.migration_run(execution_state, lease_until, created_at);

CREATE TABLE archlens.migration_artifact (
    artifact_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    artifact_kind text NOT NULL CHECK (artifact_kind IN
        ('SOURCE_SNAPSHOT','EVIDENCE','FINDING','HYPOTHESIS','PLAN','VALIDATION','DECISION','TOOL_RESULT','REPORT')),
    schema_version text NOT NULL CHECK (length(schema_version) BETWEEN 1 AND 120),
    content_hash text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    storage_ref text NOT NULL CHECK (length(storage_ref) BETWEEN 1 AND 1000),
    producer_ref text NOT NULL CHECK (length(producer_ref) BETWEEN 1 AND 200),
    imported_from_run_id uuid REFERENCES archlens.migration_run(run_id),
    imported_source_hash text CHECK (imported_source_hash IS NULL OR imported_source_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id, artifact_id),
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision),
    CHECK ((imported_from_run_id IS NULL) = (imported_source_hash IS NULL))
);
CREATE INDEX migration_artifact_scope ON archlens.migration_artifact(run_id, revision, artifact_kind);

CREATE TABLE archlens.migration_task (
    task_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    plan_version integer NOT NULL CHECK (plan_version > 0),
    task_version integer NOT NULL CHECK (task_version > 0),
    status text NOT NULL CHECK (status IN ('PLANNED','READY','RUNNING','SUCCEEDED','BLOCKED','FAILED','SKIPPED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    dependencies jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(dependencies) = 'array'),
    result_artifact_id uuid,
    skip_reason text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id, task_id),
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision),
    FOREIGN KEY (run_id, result_artifact_id) REFERENCES archlens.migration_artifact(run_id, artifact_id),
    CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL))
);

CREATE TABLE archlens.tool_invocation (
    invocation_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    task_id uuid NOT NULL,
    task_version integer NOT NULL CHECK (task_version > 0),
    action_key text NOT NULL CHECK (length(action_key) BETWEEN 1 AND 200),
    tool_id text NOT NULL CHECK (length(tool_id) BETWEEN 1 AND 120),
    tool_version text NOT NULL CHECK (length(tool_version) BETWEEN 1 AND 120),
    input_hash text NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    snapshot_hash text NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK (status IN ('REGISTERED','DISPATCHED','SUCCEEDED','FAILED','UNKNOWN')),
    result_artifact_id uuid,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision),
    FOREIGN KEY (run_id, task_id) REFERENCES archlens.migration_task(run_id, task_id),
    FOREIGN KEY (run_id, result_artifact_id) REFERENCES archlens.migration_artifact(run_id, artifact_id),
    UNIQUE(run_id, task_id, task_version, action_key, tool_id, tool_version, input_hash, snapshot_hash)
);

CREATE TABLE archlens.migration_event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    event_sequence bigint NOT NULL CHECK (event_sequence BETWEEN 1 AND 9007199254740991),
    event_type text NOT NULL CHECK (length(event_type) BETWEEN 1 AND 80),
    summary text NOT NULL CHECK (length(summary) BETWEEN 1 AND 2000),
    artifact_hash text CHECK (artifact_hash IS NULL OR artifact_hash ~ '^[0-9a-f]{64}$'),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id, event_sequence),
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision)
);

CREATE TABLE archlens.migration_question (
    question_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    question_set_hash text NOT NULL CHECK (question_set_hash ~ '^[0-9a-f]{64}$'),
    parent_artifact_hash text NOT NULL CHECK (parent_artifact_hash ~ '^[0-9a-f]{64}$'),
    field_ref text NOT NULL CHECK (length(field_ref) BETWEEN 1 AND 200),
    prompt text NOT NULL CHECK (length(prompt) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id, question_id),
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision)
);

CREATE TABLE archlens.migration_answer (
    answer_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    question_id uuid NOT NULL,
    submission_key text NOT NULL CHECK (length(submission_key) BETWEEN 1 AND 200),
    expected_revision integer NOT NULL CHECK (expected_revision > 0),
    answer_hash text NOT NULL CHECK (answer_hash ~ '^[0-9a-f]{64}$'),
    answer_json jsonb NOT NULL,
    consumed_at timestamptz,
    successor_run_id uuid REFERENCES archlens.migration_run(run_id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(question_id, submission_key),
    FOREIGN KEY (run_id, question_id) REFERENCES archlens.migration_question(run_id, question_id),
    CHECK ((consumed_at IS NULL) = (successor_run_id IS NULL))
);

CREATE TABLE archlens.validation_job (
    job_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    attempt_version integer NOT NULL CHECK (attempt_version > 0),
    plan_hash text NOT NULL CHECK (plan_hash ~ '^[0-9a-f]{64}$'),
    snapshot_hash text NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    environment_hash text NOT NULL CHECK (environment_hash ~ '^[0-9a-f]{64}$'),
    test_spec_hash text NOT NULL CHECK (test_spec_hash ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK (status IN ('REGISTERED','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
    result_artifact_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    UNIQUE(run_id, plan_hash, snapshot_hash, environment_hash, test_spec_hash, attempt_version),
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision),
    FOREIGN KEY (run_id, result_artifact_id) REFERENCES archlens.migration_artifact(run_id, artifact_id)
);

CREATE TABLE archlens.checkpoint_link (
    run_id uuid PRIMARY KEY,
    revision integer NOT NULL,
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    snapshot_hash text NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    epoch bigint NOT NULL CHECK (epoch > 0),
    graph_version text NOT NULL CHECK (length(graph_version) BETWEEN 1 AND 120),
    state_schema_version text NOT NULL CHECK (length(state_schema_version) BETWEEN 1 AND 120),
    checkpoint_namespace text NOT NULL CHECK (length(checkpoint_namespace) BETWEEN 1 AND 400),
    checkpoint_id text NOT NULL CHECK (length(checkpoint_id) BETWEEN 1 AND 400),
    checkpoint_sequence bigint NOT NULL CHECK (checkpoint_sequence >= 0),
    event_cursor bigint NOT NULL CHECK (event_cursor >= 0),
    state_hash text NOT NULL CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id, revision) REFERENCES archlens.migration_run(run_id, revision),
    FOREIGN KEY (run_id, request_hash) REFERENCES archlens.migration_run(run_id, request_hash)
);
