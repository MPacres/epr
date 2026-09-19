-- identitytenancy: minimal operational references; never patient data or browser-session state.
CREATE TABLE clinician_active_care_reservation (
    clinician_id UUID PRIMARY KEY REFERENCES platform_user_account(id),
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    encounter_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    fence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE clinician_active_care_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinician_active_care_reservation FORCE ROW LEVEL SECURITY;
CREATE POLICY active_care_operational_scope ON clinician_active_care_reservation
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true')
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');
