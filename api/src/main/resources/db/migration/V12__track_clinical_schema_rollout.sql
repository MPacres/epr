CREATE TABLE clinical_schema_rollout (
    practice_id UUID PRIMARY KEY REFERENCES practice_tenant_registry(practice_id),
    operation_id UUID NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    state VARCHAR(20) NOT NULL CHECK (state IN ('MIGRATING', 'READY', 'QUARANTINED')),
    lease_until TIMESTAMPTZ,
    target_version VARCHAR(50) NOT NULL,
    actor_id UUID NOT NULL REFERENCES platform_user_account(id),
    reason VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE clinical_schema_rollout_event (
    id UUID PRIMARY KEY,
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    operation_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE clinical_schema_rollout ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical_schema_rollout FORCE ROW LEVEL SECURITY;
CREATE POLICY clinical_rollout_scope ON clinical_schema_rollout
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true' OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID)
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');
ALTER TABLE clinical_schema_rollout_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical_schema_rollout_event FORCE ROW LEVEL SECURITY;
CREATE POLICY clinical_rollout_event_scope ON clinical_schema_rollout_event
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true')
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');

CREATE FUNCTION reject_clinical_rollout_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Clinical schema rollout history is append-only'; END; $$;
CREATE TRIGGER immutable_clinical_rollout_event BEFORE UPDATE OR DELETE ON clinical_schema_rollout_event
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_rollout_event_mutation();
