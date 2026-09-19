-- Explicit clinician-only patient operations; no inherited administrative rights.
ALTER TABLE practice_clinician_site_grant ADD COLUMN patient_directory_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE practice_clinician_site_grant ADD COLUMN patient_detail_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE practice_clinician_site_grant ADD COLUMN patient_register BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE practice_clinician_site_grant ADD COLUMN patient_demographic_edit BOOLEAN NOT NULL DEFAULT FALSE;
