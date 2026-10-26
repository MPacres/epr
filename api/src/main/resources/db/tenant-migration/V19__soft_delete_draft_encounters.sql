-- Keep draft content and audit evidence while removing withdrawn drafts from active care.
ALTER TABLE clinical_encounter
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID,
    ADD COLUMN deletion_reason VARCHAR(500),
    ADD CONSTRAINT encounter_deletion_evidence CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL AND deletion_reason IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND deletion_reason IS NOT NULL)
    );

DROP INDEX clinical_encounter_one_active_clinician;
CREATE UNIQUE INDEX clinical_encounter_one_active_clinician
    ON clinical_encounter(practice_id, responsible_clinician_id)
    WHERE care_state IN ('IN_PROGRESS', 'PAUSED') AND deleted_at IS NULL;

DROP INDEX one_active_encounter_per_queue;
CREATE UNIQUE INDEX one_active_encounter_per_queue
    ON clinical_encounter(practice_id, queue_entry_id)
    WHERE queue_entry_id IS NOT NULL AND care_state IN ('IN_PROGRESS', 'PAUSED') AND deleted_at IS NULL;

-- Longitudinal source commands can carry an optional encounter reference without
-- calling the encounter service. Lock the referenced row so a late command cannot
-- attach new clinical evidence while a draft is being withdrawn.
CREATE FUNCTION require_visible_encounter_reference() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.encounter_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM clinical_encounter
        WHERE practice_id = NEW.practice_id AND patient_id = NEW.patient_id
          AND encounter_id = NEW.encounter_id AND deleted_at IS NULL FOR SHARE
    ) THEN
        RAISE EXCEPTION 'Encounter reference is unavailable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER visible_allergy_encounter BEFORE INSERT OR UPDATE ON allergy_revision
    FOR EACH ROW EXECUTE FUNCTION require_visible_encounter_reference();
CREATE TRIGGER visible_medication_use_encounter BEFORE INSERT OR UPDATE ON medication_use_revision
    FOR EACH ROW EXECUTE FUNCTION require_visible_encounter_reference();
CREATE TRIGGER visible_problem_encounter BEFORE INSERT OR UPDATE ON problem_revision
    FOR EACH ROW EXECUTE FUNCTION require_visible_encounter_reference();
CREATE TRIGGER visible_problem_link_encounter BEFORE INSERT OR UPDATE ON problem_encounter_link
    FOR EACH ROW EXECUTE FUNCTION require_visible_encounter_reference();
CREATE TRIGGER visible_review_encounter BEFORE INSERT OR UPDATE ON clinical_list_review
    FOR EACH ROW EXECUTE FUNCTION require_visible_encounter_reference();
