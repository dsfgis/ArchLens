-- Small deterministic tool results remain in PG so a replay can read the exact persisted bytes.
-- Large source or database outputs require a separate bounded artifact store in a later task.
CREATE TABLE archlens.tool_result_payload (
    artifact_id uuid PRIMARY KEY REFERENCES archlens.migration_artifact(artifact_id),
    content_utf8 text NOT NULL CHECK (octet_length(content_utf8) BETWEEN 1 AND 65536)
);
