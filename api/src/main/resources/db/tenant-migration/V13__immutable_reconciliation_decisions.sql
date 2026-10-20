-- clinicalchart: preserve every discrepancy decision in the exact reconciliation manifest.
CREATE TABLE clinical_reconciliation_decision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    review_id UUID NOT NULL,
    decision_order INTEGER NOT NULL CHECK (decision_order >= 0),
    proposal_id UUID,
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('ALLERGY', 'MEDICATION_USE', 'PROBLEM')),
    source_id UUID,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('KEEP', 'ADD', 'UPDATE', 'STOP', 'RESOLVE', 'REJECT')),
    replacement JSONB,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    PRIMARY KEY (practice_id, patient_id, review_id, decision_order),
    FOREIGN KEY (practice_id, patient_id, review_id)
        REFERENCES clinical_list_review(practice_id, patient_id, review_id)
);
CREATE TRIGGER immutable_clinical_reconciliation_decision
    BEFORE UPDATE OR DELETE ON clinical_reconciliation_decision
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
