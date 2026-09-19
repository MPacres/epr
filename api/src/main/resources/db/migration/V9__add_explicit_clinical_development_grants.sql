-- Owner: identitytenancy. No administrator or platform role receives clinical access implicitly.
CREATE TABLE practice_clinical_capability (
    practice_id UUID PRIMARY KEY REFERENCES practice_tenant_registry(practice_id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    synthetic_only BOOLEAN NOT NULL DEFAULT TRUE CHECK (synthetic_only),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE practice_clinician_site_grant (
    practice_id UUID NOT NULL,
    user_id UUID NOT NULL,
    site_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reproductive_access BOOLEAN NOT NULL DEFAULT FALSE,
    template_approver BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    granted_by UUID NOT NULL REFERENCES platform_user_account(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, user_id, site_id),
    FOREIGN KEY (practice_id, user_id) REFERENCES practice_membership(practice_id, user_id)
);

ALTER TABLE practice_clinical_capability ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_clinical_capability FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_clinical_capability_scope ON practice_clinical_capability
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID)
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');
ALTER TABLE practice_clinician_site_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_clinician_site_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_clinician_site_grant_scope ON practice_clinician_site_grant
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID)
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');

COMMENT ON TABLE practice_clinician_site_grant IS
    'Explicit local synthetic clinical grants. Site identity is additionally checked in the routed tenant database.';
