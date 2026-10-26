-- A mistaken measurement can be removed from the clinical timeline without erasing its audit evidence.
ALTER TABLE encounter_vital_set
    ADD COLUMN removed_at TIMESTAMPTZ,
    ADD COLUMN removed_by UUID,
    ADD COLUMN removal_reason VARCHAR(100),
    ADD CONSTRAINT encounter_vital_set_removal_complete CHECK (
        (removed_at IS NULL AND removed_by IS NULL AND removal_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by IS NOT NULL AND removal_reason IS NOT NULL)
    );
