-- clinicalchart: provenance references must remain in the same Practice and patient.
ALTER TABLE allergy_revision ADD CONSTRAINT allergy_revision_encounter_fk
    FOREIGN KEY (practice_id, patient_id, encounter_id)
    REFERENCES clinical_encounter(practice_id, patient_id, encounter_id);

ALTER TABLE medication_use_revision ADD CONSTRAINT medication_use_revision_encounter_fk
    FOREIGN KEY (practice_id, patient_id, encounter_id)
    REFERENCES clinical_encounter(practice_id, patient_id, encounter_id);
ALTER TABLE medication_use_revision ADD CONSTRAINT medication_use_indication_problem_fk
    FOREIGN KEY (practice_id, patient_id, indication_problem_id)
    REFERENCES problem_record(practice_id, patient_id, problem_id);
ALTER TABLE medication_use_revision ADD CONSTRAINT medication_use_prescription_fk
    FOREIGN KEY (practice_id, patient_id, prescription_id)
    REFERENCES prescription(practice_id, patient_id, id);

ALTER TABLE problem_revision ADD CONSTRAINT problem_revision_encounter_fk
    FOREIGN KEY (practice_id, patient_id, encounter_id)
    REFERENCES clinical_encounter(practice_id, patient_id, encounter_id);
ALTER TABLE problem_encounter_link ADD CONSTRAINT problem_encounter_link_encounter_fk
    FOREIGN KEY (practice_id, patient_id, encounter_id)
    REFERENCES clinical_encounter(practice_id, patient_id, encounter_id);

ALTER TABLE clinical_list_review ADD CONSTRAINT clinical_list_review_encounter_fk
    FOREIGN KEY (practice_id, patient_id, encounter_id)
    REFERENCES clinical_encounter(practice_id, patient_id, encounter_id);
ALTER TABLE clinical_list_review ADD CONSTRAINT reconciliation_requires_encounter
    CHECK ((review_type = 'RECONCILIATION') = (encounter_id IS NOT NULL));
