-- The prototype is explicitly opt-in. Existing queued runs are not silently executed.
ALTER TABLE archlens.migration_run ADD COLUMN runtime_profile text CHECK (runtime_profile IS NULL OR runtime_profile = 'P0_RULES_V1');
ALTER TABLE archlens.migration_run ADD COLUMN started_at timestamptz;

-- Reservations survive worker loss. Uncertain dispatches are charged, never refunded on recovery.
CREATE TABLE archlens.migration_budget_reservation (
    run_id uuid NOT NULL REFERENCES archlens.migration_run(run_id),
    action_key text NOT NULL CHECK (length(action_key) BETWEEN 1 AND 200),
    resource text NOT NULL CHECK (resource IN ('TOOL','MODEL')),
    status text NOT NULL CHECK (status IN ('RESERVED','SETTLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    settled_at timestamptz,
    PRIMARY KEY(run_id,action_key,resource),
    CHECK ((status = 'SETTLED') = (settled_at IS NOT NULL))
);
REVOKE ALL ON archlens.migration_budget_reservation FROM PUBLIC;

-- Preserve pre-V005 elapsed time and already registered calls conservatively on upgrade.
UPDATE archlens.migration_run SET started_at=created_at WHERE epoch>0;
INSERT INTO archlens.migration_budget_reservation(run_id,action_key,resource,status,created_at,settled_at)
SELECT i.run_id,i.invocation_id::text || ':1','TOOL',
       CASE WHEN i.status='REGISTERED' THEN 'RESERVED' ELSE 'SETTLED' END,i.created_at,
       CASE WHEN i.status='REGISTERED' THEN NULL ELSE COALESCE(i.finished_at,i.created_at) END
FROM archlens.tool_invocation i;
