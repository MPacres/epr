-- Independently authored encounter observations. Corrections append revisions.
CREATE TABLE encounter_vital_set (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    vital_set_id UUID NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (practice_id, patient_id, encounter_id, vital_set_id),
    UNIQUE (practice_id, vital_set_id),
    FOREIGN KEY (practice_id, patient_id, encounter_id)
        REFERENCES clinical_encounter(practice_id, patient_id, encounter_id)
);

CREATE TABLE encounter_vital_revision (
    practice_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    vital_set_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    measured_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(200) NOT NULL CHECK (length(trim(source)) > 0),
    systolic_mmhg NUMERIC(6,1),
    diastolic_mmhg NUMERIC(6,1),
    pulse_bpm NUMERIC(6,1),
    respiratory_rate_bpm NUMERIC(6,1),
    temperature_c NUMERIC(5,2),
    oxygen_saturation_percent NUMERIC(5,2),
    weight_kg NUMERIC(7,2),
    height_cm NUMERIC(7,2),
    correction_reason VARCHAR(500),
    recorded_by UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (practice_id, patient_id, encounter_id, vital_set_id, version),
    FOREIGN KEY (practice_id, patient_id, encounter_id, vital_set_id)
        REFERENCES encounter_vital_set(practice_id, patient_id, encounter_id, vital_set_id),
    CHECK ((systolic_mmhg IS NULL) = (diastolic_mmhg IS NULL)),
    CHECK (systolic_mmhg IS NULL OR (systolic_mmhg > 0 AND diastolic_mmhg > 0)),
    CHECK (pulse_bpm IS NULL OR pulse_bpm > 0),
    CHECK (respiratory_rate_bpm IS NULL OR respiratory_rate_bpm > 0),
    CHECK (temperature_c IS NULL OR temperature_c > 0),
    CHECK (oxygen_saturation_percent IS NULL OR (oxygen_saturation_percent > 0 AND oxygen_saturation_percent <= 100)),
    CHECK (weight_kg IS NULL OR weight_kg > 0),
    CHECK (height_cm IS NULL OR height_cm > 0),
    CHECK (num_nonnulls(systolic_mmhg, pulse_bpm, respiratory_rate_bpm, temperature_c,
                        oxygen_saturation_percent, weight_kg, height_cm) > 0)
);
CREATE INDEX encounter_vital_revision_time ON encounter_vital_revision
    (practice_id, patient_id, encounter_id, measured_at DESC);
CREATE TRIGGER immutable_encounter_vital_revision BEFORE UPDATE OR DELETE ON encounter_vital_revision
    FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
