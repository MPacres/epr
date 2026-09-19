-- encountertemplates owns durable, explicit follow-up intents.
CREATE TABLE encounter_followup_intent (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    intent_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    due_date DATE NOT NULL,
    instruction VARCHAR(2000) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, intent_id),
    FOREIGN KEY (practice_id, patient_id, encounter_id) REFERENCES clinical_encounter(practice_id, patient_id, encounter_id)
);
CREATE TRIGGER immutable_followup_intent BEFORE UPDATE OR DELETE ON encounter_followup_intent
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
-- carecoordination owns delivery receipts and work status. Signing never resolves follow-up tasks.
CREATE TABLE clinical_owned_task (
    practice_id UUID NOT NULL REFERENCES tenant_practice(practice_id),
    source_id UUID NOT NULL,
    source_version BIGINT NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('UNSIGNED_NOTE', 'FOLLOW_UP')),
    instruction VARCHAR(2000) NOT NULL,
    due_date DATE,
    state VARCHAR(20) NOT NULL CHECK (state IN ('OPEN', 'RESOLVED')),
    version BIGINT NOT NULL DEFAULT 0,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, source_id),
    FOREIGN KEY (practice_id, patient_id) REFERENCES clinical_patient(practice_id, patient_id)
);
