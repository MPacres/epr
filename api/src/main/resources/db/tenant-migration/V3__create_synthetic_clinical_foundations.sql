-- Additive development foundations. Each group is owned by the named application module.
-- No production clinical capability is enabled by this migration.

-- platformoperations
CREATE TABLE tenant_command_receipt (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    actor_id UUID NOT NULL,
    operation VARCHAR(200) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    outcome JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, actor_id, operation, idempotency_key)
);
CREATE TABLE clinical_audit_event (
    id UUID PRIMARY KEY,
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    actor_id UUID NOT NULL,
    record_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    reason VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE clinical_outbox_event (
    id UUID PRIMARY KEY,
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    owner_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    UNIQUE (practice_id, aggregate_id, event_type)
);

-- patientregistry
CREATE SEQUENCE clinical_patient_number;
CREATE TABLE clinical_patient (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    patient_id UUID NOT NULL,
    record_number VARCHAR(40) NOT NULL DEFAULT ('SYN-' || nextval('clinical_patient_number')),
    display_name VARCHAR(200) NOT NULL CHECK (length(trim(display_name)) > 0),
    birth_date DATE,
    identity_status VARCHAR(30) NOT NULL CHECK (identity_status IN ('PROVISIONAL', 'REPORTED')),
    synthetic_only BOOLEAN NOT NULL DEFAULT TRUE CHECK (synthetic_only),
    created_by UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (practice_id, patient_id),
    UNIQUE (practice_id, record_number)
);
CREATE TABLE patient_care_assignment (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    clinician_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (practice_id, patient_id, clinician_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);

-- clinicalchart
CREATE TABLE pregnancy_episode (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    episode_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('UNCERTAIN', 'ONGOING', 'COMPLETED')),
    responsible_clinician_id UUID NOT NULL,
    source VARCHAR(500) NOT NULL CHECK (length(trim(source)) > 0),
    clinical_time TIMESTAMPTZ,
    selected_dating_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, episode_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);
CREATE TABLE pregnancy_dating_assessment (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    episode_id UUID NOT NULL,
    assessment_id UUID NOT NULL,
    estimated_date DATE,
    source_date DATE,
    source_precision VARCHAR(20) NOT NULL CHECK (source_precision IN ('EXACT', 'APPROXIMATE', 'UNKNOWN')),
    method VARCHAR(120) NOT NULL,
    source VARCHAR(500) NOT NULL,
    review_status VARCHAR(20) NOT NULL CHECK (review_status IN ('NOT_REVIEWED', 'REVIEWED')),
    created_by UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, episode_id, assessment_id),
    FOREIGN KEY (practice_id, patient_id, episode_id) REFERENCES pregnancy_episode(practice_id, patient_id, episode_id)
);
ALTER TABLE pregnancy_episode ADD CONSTRAINT episode_selected_dating_fk
    FOREIGN KEY (practice_id, patient_id, episode_id, selected_dating_id)
    REFERENCES pregnancy_dating_assessment(practice_id, patient_id, episode_id, assessment_id);
CREATE TABLE pregnancy_dating_selection (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    episode_id UUID NOT NULL,
    episode_version BIGINT NOT NULL,
    assessment_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    actor_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, episode_id, episode_version),
    FOREIGN KEY (practice_id, patient_id, episode_id, assessment_id)
        REFERENCES pregnancy_dating_assessment(practice_id, patient_id, episode_id, assessment_id)
);
CREATE TABLE obstetric_history_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    item_id UUID NOT NULL,
    version BIGINT NOT NULL,
    account VARCHAR(4000) NOT NULL,
    source VARCHAR(500) NOT NULL,
    review_status VARCHAR(20) NOT NULL CHECK (review_status IN ('NOT_REVIEWED', 'REVIEWED')),
    reason VARCHAR(500) NOT NULL,
    actor_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, item_id, version),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);

-- encountertemplates
CREATE TABLE encounter_template_draft (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    draft_id UUID NOT NULL,
    definition JSONB NOT NULL,
    checksum CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    change_summary VARCHAR(500) NOT NULL,
    approved_checksum CHAR(64),
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    approval_basis VARCHAR(500),
    created_by UUID NOT NULL,
    PRIMARY KEY (practice_id, draft_id)
);
CREATE TABLE encounter_template_release (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    release_id UUID NOT NULL,
    draft_id UUID NOT NULL,
    template_key VARCHAR(100) NOT NULL,
    business_version VARCHAR(30) NOT NULL,
    definition JSONB NOT NULL,
    checksum CHAR(64) NOT NULL,
    approved_by UUID NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    approval_basis VARCHAR(500) NOT NULL,
    published_by UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, release_id),
    UNIQUE (practice_id, template_key, business_version),
    UNIQUE (practice_id, release_id, checksum),
    FOREIGN KEY (practice_id, draft_id) REFERENCES encounter_template_draft(practice_id, draft_id)
);
CREATE TABLE encounter_template_availability (
    practice_id UUID NOT NULL,
    release_id UUID NOT NULL,
    site_id UUID NOT NULL,
    retired BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (practice_id, release_id, site_id),
    FOREIGN KEY (practice_id, release_id) REFERENCES encounter_template_release(practice_id, release_id),
    FOREIGN KEY (practice_id, site_id) REFERENCES practice_site(practice_id, site_id)
);
CREATE TABLE encounter_field_identity (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    field_id UUID NOT NULL,
    meaning JSONB NOT NULL,
    PRIMARY KEY (practice_id, field_id)
);
CREATE TABLE clinical_encounter (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    site_id UUID NOT NULL,
    responsible_clinician_id UUID NOT NULL,
    release_id UUID NOT NULL,
    release_checksum CHAR(64) NOT NULL,
    episode_id UUID,
    episode_snapshot JSONB,
    care_mode VARCHAR(30) NOT NULL CHECK (care_mode IN ('IN_PERSON', 'TELEPHONE', 'TELECONSULTATION')),
    care_state VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (care_state IN ('IN_PROGRESS', 'PAUSED', 'FINISHED')),
    note_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (note_state IN ('DRAFT', 'READY_TO_SIGN', 'SIGNED')),
    clinical_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    response_revision BIGINT NOT NULL DEFAULT 0,
    unsigned_note_owner UUID NOT NULL,
    PRIMARY KEY (practice_id, patient_id, encounter_id),
    UNIQUE (practice_id, patient_id, encounter_id, release_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id),
    FOREIGN KEY (practice_id, site_id) REFERENCES practice_site(practice_id, site_id),
    FOREIGN KEY (practice_id, release_id, release_checksum) REFERENCES encounter_template_release(practice_id, release_id, checksum),
    FOREIGN KEY (practice_id, patient_id, episode_id) REFERENCES pregnancy_episode(practice_id, patient_id, episode_id),
    CHECK ((episode_id IS NULL) = (episode_snapshot IS NULL))
);
CREATE UNIQUE INDEX clinical_encounter_one_active_clinician
    ON clinical_encounter(practice_id, responsible_clinician_id) WHERE care_state IN ('IN_PROGRESS', 'PAUSED');
CREATE TABLE encounter_response_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    release_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    answers JSONB NOT NULL,
    actor_id UUID NOT NULL,
    client_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, encounter_id, revision),
    FOREIGN KEY (practice_id, patient_id, encounter_id, release_id)
        REFERENCES clinical_encounter(practice_id, patient_id, encounter_id, release_id)
);

-- Runtime roles can append, but cannot rewrite history even through direct SQL.
CREATE FUNCTION reject_clinical_history_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Clinical history is immutable'; END;
$$;
CREATE TRIGGER immutable_command_receipt BEFORE UPDATE OR DELETE ON tenant_command_receipt
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_clinical_audit BEFORE UPDATE OR DELETE ON clinical_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_dating_assessment BEFORE UPDATE OR DELETE ON pregnancy_dating_assessment
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_dating_selection BEFORE UPDATE OR DELETE ON pregnancy_dating_selection
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_obstetric_history BEFORE UPDATE OR DELETE ON obstetric_history_revision
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_template_release BEFORE UPDATE OR DELETE ON encounter_template_release
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_response_revision BEFORE UPDATE OR DELETE ON encounter_response_revision
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

ALTER TABLE clinical_encounter ADD COLUMN reservation_fence BIGINT NOT NULL;
CREATE TABLE cancelled_encounter_start (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    encounter_id UUID NOT NULL,
    clinician_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, encounter_id)
);
CREATE TRIGGER immutable_cancelled_start BEFORE UPDATE OR DELETE ON cancelled_encounter_start
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
