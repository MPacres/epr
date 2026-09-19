-- identitytenancy owns memberships, onboarding, access evidence and command receipts.
ALTER TABLE practice_membership ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0 CHECK (access_version >= 0);
CREATE TABLE practice_member_onboarding (
    user_id UUID PRIMARY KEY REFERENCES platform_user_account(id),
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    claimed_at TIMESTAMPTZ,
    claim_key UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE practice_member_event (
    id UUID PRIMARY KEY,
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    user_id UUID NOT NULL REFERENCES platform_user_account(id),
    actor_id UUID NOT NULL REFERENCES platform_user_account(id),
    action VARCHAR(40) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_access JSONB NOT NULL,
    after_access JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX practice_member_event_history ON practice_member_event(practice_id, user_id, occurred_at DESC);
CREATE TABLE practice_member_command (
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    actor_id UUID NOT NULL REFERENCES platform_user_account(id),
    command_key UUID NOT NULL,
    operation VARCHAR(100) NOT NULL,
    fingerprint TEXT NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(practice_id, actor_id, command_key)
);
-- Persisted rate limiting works across application instances.
CREATE TABLE practice_member_resolution_limit (
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    actor_id UUID NOT NULL REFERENCES platform_user_account(id),
    window_start TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(practice_id, actor_id)
);
DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['practice_member_onboarding','practice_member_event','practice_member_command','practice_member_resolution_limit'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY team_scope ON %I USING (COALESCE(current_setting(''epr.platform_operations'',true),'''') = ''true'' OR practice_id = NULLIF(current_setting(''epr.practice_id'',true),'''')::uuid) WITH CHECK (COALESCE(current_setting(''epr.platform_operations'',true),'''') = ''true'' OR practice_id = NULLIF(current_setting(''epr.practice_id'',true),'''')::uuid)', table_name);
    END LOOP;
END $$;
CREATE FUNCTION reject_member_evidence_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Member access evidence is append-only'; END $$;
CREATE TRIGGER member_event_immutable BEFORE UPDATE OR DELETE ON practice_member_event FOR EACH ROW EXECUTE FUNCTION reject_member_evidence_change();
CREATE TRIGGER member_command_immutable BEFORE UPDATE OR DELETE ON practice_member_command FOR EACH ROW EXECUTE FUNCTION reject_member_evidence_change();
