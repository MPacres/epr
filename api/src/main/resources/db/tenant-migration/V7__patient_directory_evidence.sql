-- patientregistry owns duplicate-review evidence and derived visit summaries.
ALTER TABLE patient_duplicate_review ADD COLUMN candidate_ids JSONB NOT NULL DEFAULT '[]';
CREATE TABLE patient_visit_projection (
    practice_id UUID NOT NULL, site_id UUID NOT NULL, clinician_id UUID NOT NULL,
    patient_id UUID NOT NULL, encounter_id UUID NOT NULL, source_version BIGINT NOT NULL,
    service_time TIMESTAMPTZ NOT NULL, visit_type VARCHAR(200) NOT NULL,
    PRIMARY KEY(practice_id,site_id,clinician_id,encounter_id),
    FOREIGN KEY(practice_id,patient_id) REFERENCES clinical_patient(practice_id,patient_id)
);
CREATE INDEX patient_visit_projection_patient ON patient_visit_projection(practice_id,site_id,clinician_id,patient_id,service_time DESC);
CREATE TABLE patient_visit_projection_state (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id), site_id UUID NOT NULL,
    clinician_id UUID NOT NULL, reproductive_access BOOLEAN NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(practice_id,site_id,clinician_id)
);
