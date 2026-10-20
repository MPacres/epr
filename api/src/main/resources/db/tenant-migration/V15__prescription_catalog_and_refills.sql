ALTER TABLE prescribing_catalog
 ADD COLUMN strength VARCHAR(200),
 ADD COLUMN formulation VARCHAR(200),
 ADD COLUMN dose_unit VARCHAR(80),
 ADD COLUMN quantity_unit VARCHAR(80);

UPDATE prescribing_catalog SET strength='100 synthetic units', formulation='Synthetic tablet', dose_unit='synthetic dose units', quantity_unit='synthetic tablets'
 WHERE code='SYN-MED-A' AND version='synthetic-1';
UPDATE prescribing_catalog SET strength='50 synthetic units', formulation='Synthetic liquid', dose_unit='synthetic dose units', quantity_unit='synthetic mL'
 WHERE code='SYN-MED-B' AND version='synthetic-1';

ALTER TABLE prescription_item_revision
 ADD COLUMN source_prescription_id UUID,
 ADD COLUMN source_item_id UUID,
 ADD CONSTRAINT prescription_refill_source_pair CHECK ((source_prescription_id IS NULL) = (source_item_id IS NULL)),
 ADD CONSTRAINT prescription_refill_source_fk FOREIGN KEY (practice_id, source_prescription_id) REFERENCES prescription(practice_id, id);
