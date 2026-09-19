-- Owner: patientregistry. Additive synthetic registration; existing UUIDs and MRNs remain intact.
ALTER TABLE clinical_patient ADD COLUMN first_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN middle_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN last_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN suffix VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN birth_accuracy VARCHAR(200) NOT NULL DEFAULT 'unknown';
ALTER TABLE clinical_patient ADD COLUMN sex VARCHAR(200) NOT NULL DEFAULT 'Not recorded';
ALTER TABLE clinical_patient ADD COLUMN phone VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN email VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN language VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN communication VARCHAR(200) NOT NULL DEFAULT 'Not recorded';
ALTER TABLE clinical_patient ADD COLUMN street VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN barangay VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN city VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN province VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN postal_code VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN philhealth_role VARCHAR(200) NOT NULL DEFAULT 'Unknown';
ALTER TABLE clinical_patient ADD COLUMN contributor VARCHAR(200) NOT NULL DEFAULT 'Unknown';
ALTER TABLE clinical_patient ADD COLUMN information_source VARCHAR(200) NOT NULL DEFAULT 'Not recorded';
ALTER TABLE clinical_patient ADD COLUMN principal_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN principal_relationship VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN representative_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN representative_relationship VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN representative_phone VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN representative_authority VARCHAR(200) NOT NULL DEFAULT 'Not recorded';
ALTER TABLE clinical_patient ADD COLUMN emergency_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN emergency_relationship VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN emergency_phone VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN other_id_type VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN other_id_issuer VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE clinical_patient ADD COLUMN pin_unavailable BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE clinical_patient ADD COLUMN protected_identifiers TEXT;
ALTER TABLE clinical_patient ADD COLUMN identifier_verification VARCHAR(30) NOT NULL DEFAULT 'NOT_VERIFIED' CHECK (identifier_verification = 'NOT_VERIFIED');
-- Do not guess how an existing unsplit name divides into given/family names.
ALTER TABLE clinical_patient ADD COLUMN original_display_name VARCHAR(200);
UPDATE clinical_patient SET original_display_name = display_name,
    birth_accuracy = CASE WHEN birth_date IS NULL THEN 'unknown' ELSE 'exact' END;
ALTER TABLE clinical_patient ADD CONSTRAINT patient_birth_accuracy CHECK
    ((birth_accuracy = 'unknown' AND birth_date IS NULL) OR (birth_accuracy IN ('exact','estimated') AND birth_date IS NOT NULL));
CREATE INDEX patient_directory_name ON clinical_patient(practice_id, lower(display_name), patient_id);
CREATE TABLE patient_demographic_revision (
    practice_id UUID NOT NULL, patient_id UUID NOT NULL, version BIGINT NOT NULL,
    actor_id UUID NOT NULL, reason VARCHAR(1000) NOT NULL, protected_evidence TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(practice_id, patient_id, version),
    FOREIGN KEY(practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);
CREATE FUNCTION preserve_patient_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Patient demographic revisions are append-only'; END $$;
CREATE TRIGGER immutable_patient_revision BEFORE UPDATE OR DELETE ON patient_demographic_revision
    FOR EACH ROW EXECUTE FUNCTION preserve_patient_revision();
CREATE TABLE patient_duplicate_review (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id), review_id UUID NOT NULL,
    actor_id UUID NOT NULL, site_id UUID NOT NULL, request_hash CHAR(64) NOT NULL,
    candidate_hash CHAR(64) NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(30), decided_at TIMESTAMPTZ, patient_id UUID,
    PRIMARY KEY(practice_id, review_id),
    FOREIGN KEY(practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);
CREATE TABLE patient_recent_open (
    practice_id UUID NOT NULL, actor_id UUID NOT NULL, patient_id UUID NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(practice_id, actor_id, patient_id),
    FOREIGN KEY(practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);
