CREATE TABLE clinician_session_selection (
    clinician_id UUID PRIMARY KEY REFERENCES platform_user_account(id),
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    session_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    selected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE clinician_session_selection ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinician_session_selection FORCE ROW LEVEL SECURITY;
CREATE POLICY clinician_session_operational_scope ON clinician_session_selection
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true')
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');
