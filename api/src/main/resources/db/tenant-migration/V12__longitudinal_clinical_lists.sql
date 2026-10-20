-- clinicalchart: longitudinal allergy, reported medication-use, problem, proposal,
-- and exact list-review history. Source revisions and review evidence are immutable.

CREATE TABLE clinical_list_state (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM')),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    PRIMARY KEY (practice_id, patient_id, source_kind),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);

CREATE TABLE allergy_record (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    allergy_id UUID NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    clinical_status VARCHAR(24) NOT NULL CHECK (clinical_status IN ('ACTIVE', 'INACTIVE', 'RESOLVED', 'REFUTED', 'ENTERED_IN_ERROR')),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, allergy_id),
    UNIQUE (practice_id, allergy_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);

CREATE TABLE allergy_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    allergy_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    substance_display VARCHAR(300) NOT NULL CHECK (length(trim(substance_display)) > 0),
    terminology_system VARCHAR(200),
    terminology_code VARCHAR(120),
    terminology_version VARCHAR(80),
    category VARCHAR(24) NOT NULL CHECK (category IN ('MEDICATION', 'FOOD', 'ENVIRONMENT', 'BIOLOGIC', 'OTHER')),
    reaction_type VARCHAR(24) NOT NULL CHECK (reaction_type IN ('ALLERGY', 'INTOLERANCE', 'UNCERTAIN')),
    verification_status VARCHAR(24) NOT NULL CHECK (verification_status IN ('UNCONFIRMED', 'CONFIRMED', 'REFUTED', 'ENTERED_IN_ERROR')),
    criticality VARCHAR(24) NOT NULL CHECK (criticality IN ('LOW', 'HIGH', 'UNABLE_TO_ASSESS')),
    onset_value VARCHAR(120),
    onset_precision VARCHAR(24) NOT NULL CHECK (onset_precision IN ('EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN')),
    last_occurrence TIMESTAMPTZ,
    information_source VARCHAR(200) NOT NULL CHECK (length(trim(information_source)) > 0),
    asserter VARCHAR(200),
    notes VARCHAR(4000),
    clinical_time TIMESTAMPTZ,
    encounter_id UUID,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    actor_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, allergy_id, version),
    FOREIGN KEY (practice_id, patient_id, allergy_id) REFERENCES allergy_record(practice_id, patient_id, allergy_id),
    CHECK ((terminology_system IS NULL) = (terminology_code IS NULL))
);

CREATE TABLE allergy_reaction_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    allergy_id UUID NOT NULL,
    version BIGINT NOT NULL,
    reaction_id UUID NOT NULL,
    manifestation VARCHAR(300) NOT NULL CHECK (length(trim(manifestation)) > 0),
    severity VARCHAR(24) NOT NULL CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'UNKNOWN')),
    onset_value VARCHAR(120),
    exposure_route VARCHAR(120),
    PRIMARY KEY (practice_id, patient_id, allergy_id, version, reaction_id),
    FOREIGN KEY (practice_id, patient_id, allergy_id, version)
        REFERENCES allergy_revision(practice_id, patient_id, allergy_id, version)
);

CREATE TABLE medication_use_record (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    medication_use_id UUID NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE', 'ON_HOLD', 'STOPPED', 'COMPLETED', 'UNCERTAIN', 'ENTERED_IN_ERROR')),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, medication_use_id),
    UNIQUE (practice_id, medication_use_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);

CREATE TABLE medication_use_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    medication_use_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    medication_display VARCHAR(300) NOT NULL CHECK (length(trim(medication_display)) > 0),
    terminology_system VARCHAR(200),
    terminology_code VARCHAR(120),
    terminology_version VARCHAR(80),
    brand VARCHAR(200),
    strength VARCHAR(120),
    formulation VARCHAR(120),
    dose NUMERIC(18,6),
    dose_unit VARCHAR(80),
    route VARCHAR(120),
    frequency VARCHAR(200),
    prn BOOLEAN NOT NULL DEFAULT FALSE,
    effective_start VARCHAR(120),
    effective_end VARCHAR(120),
    effective_precision VARCHAR(24) NOT NULL CHECK (effective_precision IN ('EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN')),
    information_source VARCHAR(200) NOT NULL CHECK (length(trim(information_source)) > 0),
    prescriber VARCHAR(200),
    adherence VARCHAR(32) NOT NULL CHECK (adherence IN ('TAKING_AS_REPORTED', 'TAKING_DIFFERENTLY', 'NOT_TAKING', 'UNKNOWN')),
    notes VARCHAR(4000),
    indication_problem_id UUID,
    prescription_id UUID,
    clinical_time TIMESTAMPTZ,
    encounter_id UUID,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    actor_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, medication_use_id, version),
    FOREIGN KEY (practice_id, patient_id, medication_use_id) REFERENCES medication_use_record(practice_id, patient_id, medication_use_id),
    CHECK ((terminology_system IS NULL) = (terminology_code IS NULL)),
    CHECK ((dose IS NULL) = (dose_unit IS NULL)),
    CHECK (dose IS NULL OR dose > 0)
);

CREATE TABLE problem_record (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    problem_id UUID NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    clinical_status VARCHAR(24) NOT NULL CHECK (clinical_status IN ('ACTIVE', 'INACTIVE', 'RESOLVED', 'REFUTED', 'ENTERED_IN_ERROR')),
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority >= 0),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, problem_id),
    UNIQUE (practice_id, problem_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);

CREATE TABLE problem_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    problem_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    problem_display VARCHAR(300) NOT NULL CHECK (length(trim(problem_display)) > 0),
    terminology_system VARCHAR(200),
    terminology_code VARCHAR(120),
    terminology_version VARCHAR(80),
    verification_status VARCHAR(24) NOT NULL CHECK (verification_status IN ('SUSPECTED', 'CONFIRMED', 'REFUTED', 'ENTERED_IN_ERROR')),
    onset_value VARCHAR(120),
    onset_precision VARCHAR(24) NOT NULL CHECK (onset_precision IN ('EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN')),
    abatement_value VARCHAR(120),
    abatement_precision VARCHAR(24) NOT NULL CHECK (abatement_precision IN ('EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN')),
    severity VARCHAR(120),
    information_source VARCHAR(200) NOT NULL CHECK (length(trim(information_source)) > 0),
    notes VARCHAR(4000),
    clinical_time TIMESTAMPTZ,
    encounter_id UUID,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    actor_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, problem_id, version),
    FOREIGN KEY (practice_id, patient_id, problem_id) REFERENCES problem_record(practice_id, patient_id, problem_id),
    CHECK ((terminology_system IS NULL) = (terminology_code IS NULL))
);

CREATE TABLE problem_encounter_link (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    problem_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    linked_by UUID NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, problem_id, encounter_id),
    FOREIGN KEY (practice_id, patient_id, problem_id) REFERENCES problem_record(practice_id, patient_id, problem_id)
);

CREATE TABLE clinical_list_review (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    review_id UUID NOT NULL,
    site_id UUID NOT NULL,
    review_type VARCHAR(24) NOT NULL CHECK (review_type IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM', 'RECONCILIATION')),
    encounter_id UUID,
    outcome VARCHAR(48) NOT NULL CHECK (outcome IN ('REVIEWED_KNOWN', 'REVIEWED_NONE_KNOWN', 'REVIEWED_NO_KNOWN_MEDICATION_ALLERGIES', 'UNABLE_TO_CONFIRM', 'RECONCILED')),
    source VARCHAR(2000) NOT NULL CHECK (length(trim(source)) > 0),
    interactions_unavailable_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by UUID NOT NULL,
    client_time TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    manifest_hash CHAR(64) NOT NULL,
    PRIMARY KEY (practice_id, patient_id, review_id),
    UNIQUE (practice_id, review_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id),
    FOREIGN KEY (practice_id, site_id) REFERENCES practice_site(practice_id, site_id)
);

CREATE TABLE clinical_list_review_kind (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    review_id UUID NOT NULL,
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM')),
    source_generation BIGINT NOT NULL CHECK (source_generation >= 0),
    assertion VARCHAR(48) NOT NULL CHECK (assertion IN ('KNOWN_VALUES', 'NONE_KNOWN', 'NO_KNOWN_MEDICATION_ALLERGIES', 'UNABLE_TO_CONFIRM')),
    PRIMARY KEY (practice_id, patient_id, review_id, source_kind),
    FOREIGN KEY (practice_id, patient_id, review_id) REFERENCES clinical_list_review(practice_id, patient_id, review_id)
);

CREATE TABLE clinical_list_review_item (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    review_id UUID NOT NULL,
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM')),
    source_id UUID NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version >= 0),
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('KEEP', 'ADD', 'UPDATE', 'STOP', 'RESOLVE', 'REJECT', 'REVIEWED')),
    PRIMARY KEY (practice_id, patient_id, review_id, source_kind, source_id),
    FOREIGN KEY (practice_id, patient_id, review_id) REFERENCES clinical_list_review(practice_id, patient_id, review_id)
);

CREATE TABLE clinical_record_proposal (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    site_id UUID NOT NULL,
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM')),
    proposed_action VARCHAR(24) NOT NULL CHECK (proposed_action IN ('ADD', 'UPDATE', 'STOP', 'RESOLVE')),
    target_id UUID,
    payload JSONB NOT NULL,
    information_source VARCHAR(200) NOT NULL CHECK (length(trim(information_source)) > 0),
    proposed_by UUID NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, proposal_id),
    UNIQUE (practice_id, proposal_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id),
    FOREIGN KEY (practice_id, site_id) REFERENCES practice_site(practice_id, site_id)
);

CREATE TABLE proposal_decision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('ACCEPTED', 'EDITED_AND_ACCEPTED', 'REJECTED')),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    decided_by UUID NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    review_id UUID NOT NULL,
    PRIMARY KEY (practice_id, patient_id, proposal_id),
    FOREIGN KEY (practice_id, patient_id, proposal_id) REFERENCES clinical_record_proposal(practice_id, patient_id, proposal_id),
    FOREIGN KEY (practice_id, patient_id, review_id) REFERENCES clinical_list_review(practice_id, patient_id, review_id)
);

CREATE INDEX allergy_record_active ON allergy_record(practice_id, patient_id, clinical_status, created_at DESC);
CREATE INDEX medication_use_record_current ON medication_use_record(practice_id, patient_id, status, created_at DESC);
CREATE INDEX problem_record_ongoing ON problem_record(practice_id, patient_id, clinical_status, priority, created_at DESC);
CREATE INDEX clinical_list_review_context ON clinical_list_review(practice_id, patient_id, review_type, reviewed_at DESC);
CREATE INDEX clinical_record_proposal_patient ON clinical_record_proposal(practice_id, patient_id, proposed_at);

CREATE TRIGGER immutable_allergy_revision BEFORE UPDATE OR DELETE ON allergy_revision FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_allergy_reaction_revision BEFORE UPDATE OR DELETE ON allergy_reaction_revision FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_medication_use_revision BEFORE UPDATE OR DELETE ON medication_use_revision FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_problem_revision BEFORE UPDATE OR DELETE ON problem_revision FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_problem_encounter_link BEFORE UPDATE OR DELETE ON problem_encounter_link FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_clinical_list_review BEFORE UPDATE OR DELETE ON clinical_list_review FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_clinical_list_review_kind BEFORE UPDATE OR DELETE ON clinical_list_review_kind FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_clinical_list_review_item BEFORE UPDATE OR DELETE ON clinical_list_review_item FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_clinical_record_proposal BEFORE UPDATE OR DELETE ON clinical_record_proposal FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_proposal_decision BEFORE UPDATE OR DELETE ON proposal_decision FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

-- Bind prepared and signed note bytes to an exact clinical-list reconciliation.
ALTER TABLE encounter_sign_preparation ADD COLUMN clinical_review_id UUID;
ALTER TABLE encounter_sign_preparation ADD COLUMN clinical_review_hash CHAR(64);

-- New prescription issuance binds the canonical clinical-list reconciliation.
ALTER TABLE prescription_preparation ADD COLUMN clinical_review_id UUID;
ALTER TABLE prescription_preparation ADD COLUMN clinical_review_hash CHAR(64);
