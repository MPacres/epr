-- identitytenancy: independent clinical action grants; no administrative inheritance.
CREATE TABLE practice_intervention_capability (
 practice_id UUID NOT NULL REFERENCES practice_tenant_registry(practice_id),
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('PRESCRIPTION','LAB_ORDER','IMAGING_ORDER','REFERRAL','FOLLOW_UP')),
 preparation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 authorization_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(practice_id,kind)
);
CREATE TABLE practice_intervention_grant (
 practice_id UUID NOT NULL, user_id UUID NOT NULL, site_id UUID NOT NULL,
 kind VARCHAR(24) NOT NULL, can_read BOOLEAN NOT NULL DEFAULT FALSE,
 can_prepare BOOLEAN NOT NULL DEFAULT FALSE, can_authorize BOOLEAN NOT NULL DEFAULT FALSE,
 reason VARCHAR(500) NOT NULL CHECK(length(trim(reason))>0),
 granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,user_id,site_id,kind),
 FOREIGN KEY(practice_id,user_id,site_id) REFERENCES practice_clinician_site_grant(practice_id,user_id,site_id),
 FOREIGN KEY(practice_id,kind) REFERENCES practice_intervention_capability(practice_id,kind)
);
ALTER TABLE practice_intervention_capability ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_intervention_capability FORCE ROW LEVEL SECURITY;
CREATE POLICY intervention_capability_scope ON practice_intervention_capability
 USING(COALESCE(current_setting('epr.platform_operations',TRUE),'')='true' OR practice_id=NULLIF(current_setting('epr.practice_id',TRUE),'')::UUID)
 WITH CHECK(COALESCE(current_setting('epr.platform_operations',TRUE),'')='true');
ALTER TABLE practice_intervention_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_intervention_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY intervention_grant_scope ON practice_intervention_grant
 USING(COALESCE(current_setting('epr.platform_operations',TRUE),'')='true' OR practice_id=NULLIF(current_setting('epr.practice_id',TRUE),'')::UUID)
 WITH CHECK(COALESCE(current_setting('epr.platform_operations',TRUE),'')='true');
