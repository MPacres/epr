-- identitytenancy: explicit longitudinal clinical-list capabilities and grants.
-- Administrative or intervention authority does not imply chart-list access.
CREATE TABLE practice_clinical_chart_capability (
    practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (practice_id, source_kind)
);

CREATE TABLE practice_clinical_chart_grant (
    practice_id UUID NOT NULL,
    user_id UUID NOT NULL,
    site_id UUID NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    can_read BOOLEAN NOT NULL DEFAULT FALSE,
    can_propose BOOLEAN NOT NULL DEFAULT FALSE,
    can_write BOOLEAN NOT NULL DEFAULT FALSE,
    can_review BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, user_id, site_id, source_kind),
    FOREIGN KEY (practice_id, user_id, site_id)
        REFERENCES practice_clinician_site_grant(practice_id, user_id, site_id),
    FOREIGN KEY (practice_id, source_kind)
        REFERENCES practice_clinical_chart_capability(practice_id, source_kind)
);

ALTER TABLE practice_clinical_chart_capability ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_clinical_chart_capability FORCE ROW LEVEL SECURITY;
CREATE POLICY clinical_chart_capability_scope ON practice_clinical_chart_capability
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID)
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');

ALTER TABLE practice_clinical_chart_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_clinical_chart_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY clinical_chart_grant_scope ON practice_clinical_chart_grant
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID)
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');

-- Existing explicitly enabled synthetic clinicians receive the new local-only actions.
INSERT INTO practice_clinical_chart_capability(practice_id, source_kind, enabled)
SELECT capability.practice_id, kind.source_kind, TRUE
FROM practice_clinical_capability capability
CROSS JOIN (VALUES ('ALLERGY'), ('MEDICATION_USE'), ('PROBLEM')) kind(source_kind)
WHERE capability.enabled;

INSERT INTO practice_clinical_chart_grant(
    practice_id, user_id, site_id, source_kind,
    can_read, can_propose, can_write, can_review, reason
)
SELECT grant_row.practice_id, grant_row.user_id, grant_row.site_id, kind.source_kind,
       TRUE, TRUE, TRUE, TRUE, 'Synthetic clinical-list rollout grant'
FROM practice_clinician_site_grant grant_row
CROSS JOIN (VALUES ('ALLERGY'), ('MEDICATION_USE'), ('PROBLEM')) kind(source_kind)
JOIN practice_clinical_capability capability
  ON capability.practice_id = grant_row.practice_id AND capability.enabled
WHERE grant_row.enabled;
