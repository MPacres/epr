-- schedulingqueue owns persisted sessions and queue entries.
CREATE TABLE clinical_practice_session (
    practice_id UUID NOT NULL,
    site_id UUID NOT NULL,
    session_id UUID NOT NULL,
    clinician_id UUID NOT NULL,
    session_date DATE NOT NULL,
    label VARCHAR(120) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('DRAFT', 'OPEN', 'IN_PROGRESS', 'CLOSING', 'CLOSED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (practice_id, session_id),
    FOREIGN KEY (practice_id, site_id) REFERENCES practice_site(practice_id, site_id)
);
CREATE TABLE clinical_queue_entry (
    practice_id UUID NOT NULL,
    session_id UUID NOT NULL,
    queue_entry_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    state VARCHAR(40) NOT NULL CHECK (state IN ('EXPECTED', 'CHECKED_IN', 'WAITING_FOR_PREPARATION', 'READY_FOR_DOCTOR', 'CALLED', 'IN_CONSULTATION', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'LEFT_WITHOUT_BEING_SEEN', 'TRANSFERRED', 'DEFERRED')),
    encounter_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, session_id, queue_entry_id),
    FOREIGN KEY (practice_id, session_id) REFERENCES clinical_practice_session(practice_id, session_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id),
    FOREIGN KEY (practice_id, patient_id, encounter_id) REFERENCES clinical_encounter(practice_id, patient_id, encounter_id)
);
CREATE UNIQUE INDEX queue_patient_active_once ON clinical_queue_entry(practice_id, session_id, patient_id)
    WHERE state IN ('EXPECTED', 'CHECKED_IN', 'WAITING_FOR_PREPARATION', 'READY_FOR_DOCTOR', 'CALLED', 'IN_CONSULTATION');
ALTER TABLE clinical_encounter ADD COLUMN session_id UUID;
ALTER TABLE clinical_encounter ADD COLUMN queue_entry_id UUID;
ALTER TABLE clinical_encounter ADD COLUMN handoff_snapshot JSONB;
ALTER TABLE clinical_encounter ADD CONSTRAINT encounter_queue_fk FOREIGN KEY (practice_id, session_id, queue_entry_id)
    REFERENCES clinical_queue_entry(practice_id, session_id, queue_entry_id);
ALTER TABLE clinical_encounter ADD CONSTRAINT encounter_queue_links CHECK ((session_id IS NULL) = (queue_entry_id IS NULL));
CREATE UNIQUE INDEX one_active_encounter_per_queue ON clinical_encounter(practice_id, queue_entry_id)
    WHERE queue_entry_id IS NOT NULL AND care_state IN ('IN_PROGRESS', 'PAUSED');

-- documentscommunication owns verified artifact metadata; local bytes remain outside the DB transaction.
CREATE TABLE private_clinical_artifact (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    artifact_id UUID NOT NULL,
    sha256 CHAR(64) NOT NULL,
    byte_length BIGINT NOT NULL CHECK (byte_length > 0 AND byte_length <= 2000000),
    state VARCHAR(20) NOT NULL CHECK (state IN ('STAGED', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, artifact_id)
);
-- encountertemplates owns revision-bound preparations, immutable signatures, and linked amendments.
CREATE TABLE encounter_sign_preparation (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    preparation_id UUID NOT NULL,
    response_revision BIGINT NOT NULL,
    encounter_version BIGINT NOT NULL,
    release_checksum CHAR(64) NOT NULL,
    artifact_id UUID NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, encounter_id, preparation_id),
    FOREIGN KEY (practice_id, patient_id, encounter_id, response_revision)
        REFERENCES encounter_response_revision(practice_id, patient_id, encounter_id, revision),
    FOREIGN KEY (practice_id, artifact_id) REFERENCES private_clinical_artifact(practice_id, artifact_id)
);
CREATE TABLE encounter_signature (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    signature_id UUID NOT NULL,
    preparation_id UUID NOT NULL,
    response_revision BIGINT NOT NULL,
    artifact_id UUID NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    signer_id UUID NOT NULL,
    assurance_method VARCHAR(80) NOT NULL CHECK (assurance_method = 'LOCAL_SYNTHETIC_CONFIRMATION'),
    signed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    amendment_of UUID,
    amendment_reason VARCHAR(500),
    PRIMARY KEY (practice_id, patient_id, encounter_id, signature_id),
    UNIQUE (practice_id, patient_id, encounter_id, preparation_id),
    FOREIGN KEY (practice_id, patient_id, encounter_id, preparation_id)
        REFERENCES encounter_sign_preparation(practice_id, patient_id, encounter_id, preparation_id),
    FOREIGN KEY (practice_id, patient_id, encounter_id, amendment_of)
        REFERENCES encounter_signature(practice_id, patient_id, encounter_id, signature_id),
    FOREIGN KEY (practice_id, artifact_id) REFERENCES private_clinical_artifact(practice_id, artifact_id),
    CHECK ((amendment_of IS NULL) = (amendment_reason IS NULL))
);
ALTER TABLE clinical_encounter ADD COLUMN amendment_of_signature_id UUID;
ALTER TABLE clinical_encounter ADD COLUMN amendment_reason VARCHAR(500);
ALTER TABLE clinical_encounter ADD CONSTRAINT encounter_amendment_fk
    FOREIGN KEY (practice_id, patient_id, encounter_id, amendment_of_signature_id)
    REFERENCES encounter_signature(practice_id, patient_id, encounter_id, signature_id);
CREATE TRIGGER immutable_sign_preparation BEFORE UPDATE OR DELETE ON encounter_sign_preparation
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_signature BEFORE UPDATE OR DELETE ON encounter_signature
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

ALTER TABLE clinical_encounter ADD CONSTRAINT encounter_uuid_unique_in_practice UNIQUE(practice_id, encounter_id);
ALTER TABLE pregnancy_episode ADD CONSTRAINT episode_uuid_unique_in_practice UNIQUE(practice_id, episode_id);
ALTER TABLE pregnancy_dating_assessment ADD CONSTRAINT dating_uuid_unique_in_practice UNIQUE(practice_id, assessment_id);
ALTER TABLE clinical_queue_entry ADD CONSTRAINT queue_uuid_unique_in_practice UNIQUE(practice_id, queue_entry_id);
