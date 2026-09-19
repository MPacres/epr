-- encountertemplates owns the following association and immutable snapshot tables.
CREATE TABLE encounter_intervention_set (
 practice_id UUID NOT NULL, patient_id UUID NOT NULL, encounter_id UUID NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(practice_id,patient_id,encounter_id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id)
);
CREATE TABLE encounter_intervention_link (
 practice_id UUID NOT NULL, patient_id UUID NOT NULL, encounter_id UUID NOT NULL,
 link_id UUID NOT NULL, kind VARCHAR(24) NOT NULL, source_id UUID NOT NULL,
 current_revision BIGINT NOT NULL, created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,link_id),
 UNIQUE(practice_id,kind,source_id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id)
);
CREATE TABLE encounter_intervention_summary (
 practice_id UUID NOT NULL, link_id UUID NOT NULL, revision BIGINT NOT NULL,
 source_version BIGINT NOT NULL, summary JSONB NOT NULL, checksum VARCHAR(64) NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,link_id,revision),
 FOREIGN KEY(practice_id,link_id) REFERENCES encounter_intervention_link(practice_id,link_id)
);
CREATE TRIGGER immutable_intervention_summary BEFORE UPDATE OR DELETE ON encounter_intervention_summary
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TABLE encounter_intervention_manifest (
 practice_id UUID NOT NULL, patient_id UUID NOT NULL, encounter_id UUID NOT NULL,
 manifest_id UUID NOT NULL, set_version BIGINT NOT NULL, items JSONB NOT NULL,
 checksum VARCHAR(64) NOT NULL, created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,manifest_id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id)
);
CREATE TRIGGER immutable_intervention_manifest BEFORE UPDATE OR DELETE ON encounter_intervention_manifest
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
ALTER TABLE encounter_sign_preparation ADD COLUMN intervention_set_version BIGINT;
ALTER TABLE encounter_sign_preparation ADD COLUMN intervention_manifest_id UUID;
ALTER TABLE encounter_sign_preparation ADD CONSTRAINT sign_manifest_fk FOREIGN KEY(practice_id,intervention_manifest_id)
 REFERENCES encounter_intervention_manifest(practice_id,manifest_id);

-- ordersresults owns service_request and its append-only revisions/evidence.
CREATE TABLE service_request (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,encounter_id UUID NOT NULL,site_id UUID NOT NULL,
 id UUID NOT NULL,version BIGINT NOT NULL DEFAULT 0,state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 delivery_state VARCHAR(30) NOT NULL DEFAULT 'NOT_RELEASED',fulfillment_state VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
 review_state VARCHAR(30) NOT NULL DEFAULT 'NOT_APPLICABLE',owner_id UUID NOT NULL,
 author_id UUID NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 authorized_at TIMESTAMPTZ,artifact_id UUID,artifact_hash VARCHAR(64),supersedes_id UUID,
 PRIMARY KEY(practice_id,id),UNIQUE(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id),
 FOREIGN KEY(practice_id,site_id) REFERENCES practice_site(practice_id,site_id),
 FOREIGN KEY(practice_id,patient_id,supersedes_id) REFERENCES service_request(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TABLE service_request_revision (
 practice_id UUID NOT NULL,id UUID NOT NULL,version BIGINT NOT NULL,
 kind VARCHAR(4000),group_id UUID,catalog_code VARCHAR(4000),catalog_version VARCHAR(4000),service_name VARCHAR(4000),indication VARCHAR(4000),priority VARCHAR(4000),destination VARCHAR(4000),specimen VARCHAR(4000),body_site VARCHAR(4000),laterality VARCHAR(4000),instructions VARCHAR(4000),requested_date DATE,
 state VARCHAR(24) NOT NULL,delivery_state VARCHAR(30) NOT NULL,fulfillment_state VARCHAR(30) NOT NULL,review_state VARCHAR(30) NOT NULL,
 owner_id UUID NOT NULL,artifact_id UUID,artifact_hash VARCHAR(64),reason VARCHAR(2000),recorded_by UUID NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,id,version),FOREIGN KEY(practice_id,id) REFERENCES service_request(practice_id,id)
);
CREATE TRIGGER immutable_service_request_revision BEFORE UPDATE OR DELETE ON service_request_revision
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TABLE service_request_preparation (
 practice_id UUID NOT NULL,id UUID NOT NULL,preparation_id UUID NOT NULL,source_version BIGINT NOT NULL,
 artifact_id UUID NOT NULL,artifact_hash VARCHAR(64) NOT NULL,prepared_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,preparation_id),FOREIGN KEY(practice_id,id) REFERENCES service_request(practice_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TRIGGER immutable_service_request_preparation BEFORE UPDATE OR DELETE ON service_request_preparation
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

-- prescribing owns prescription and its append-only revisions/evidence.
CREATE TABLE prescription (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,encounter_id UUID NOT NULL,site_id UUID NOT NULL,
 id UUID NOT NULL,version BIGINT NOT NULL DEFAULT 0,state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 delivery_state VARCHAR(30) NOT NULL DEFAULT 'NOT_RELEASED',fulfillment_state VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
 review_state VARCHAR(30) NOT NULL DEFAULT 'NOT_APPLICABLE',owner_id UUID NOT NULL,
 author_id UUID NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 authorized_at TIMESTAMPTZ,artifact_id UUID,artifact_hash VARCHAR(64),supersedes_id UUID,
 PRIMARY KEY(practice_id,id),UNIQUE(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id),
 FOREIGN KEY(practice_id,site_id) REFERENCES practice_site(practice_id,site_id),
 FOREIGN KEY(practice_id,patient_id,supersedes_id) REFERENCES prescription(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TABLE prescription_revision (
 practice_id UUID NOT NULL,id UUID NOT NULL,version BIGINT NOT NULL,
 indication VARCHAR(4000),instructions VARCHAR(4000),review_id UUID,
 state VARCHAR(24) NOT NULL,delivery_state VARCHAR(30) NOT NULL,fulfillment_state VARCHAR(30) NOT NULL,review_state VARCHAR(30) NOT NULL,
 owner_id UUID NOT NULL,artifact_id UUID,artifact_hash VARCHAR(64),reason VARCHAR(2000),recorded_by UUID NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,id,version),FOREIGN KEY(practice_id,id) REFERENCES prescription(practice_id,id)
);
CREATE TRIGGER immutable_prescription_revision BEFORE UPDATE OR DELETE ON prescription_revision
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TABLE prescription_preparation (
 practice_id UUID NOT NULL,id UUID NOT NULL,preparation_id UUID NOT NULL,source_version BIGINT NOT NULL,
 artifact_id UUID NOT NULL,artifact_hash VARCHAR(64) NOT NULL,prepared_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,preparation_id),FOREIGN KEY(practice_id,id) REFERENCES prescription(practice_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TRIGGER immutable_prescription_preparation BEFORE UPDATE OR DELETE ON prescription_preparation
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TABLE prescription_item_revision (
 practice_id UUID NOT NULL,prescription_id UUID NOT NULL,version BIGINT NOT NULL,item_id UUID NOT NULL,
 ingredient VARCHAR(200) NOT NULL,brand VARCHAR(200),strength VARCHAR(200),formulation VARCHAR(200),
 dose NUMERIC,dose_unit VARCHAR(80),route VARCHAR(100),frequency VARCHAR(200),duration VARCHAR(200),
 quantity NUMERIC,quantity_unit VARCHAR(80),repeats INTEGER,instructions VARCHAR(2000),catalog_code VARCHAR(100),catalog_version VARCHAR(100),
 PRIMARY KEY(practice_id,prescription_id,version,item_id),
 FOREIGN KEY(practice_id,prescription_id,version) REFERENCES prescription_revision(practice_id,id,version)
);
CREATE TRIGGER immutable_prescription_item BEFORE UPDATE OR DELETE ON prescription_item_revision
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

-- carecoordination owns referral and its append-only revisions/evidence.
CREATE TABLE referral (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,encounter_id UUID NOT NULL,site_id UUID NOT NULL,
 id UUID NOT NULL,version BIGINT NOT NULL DEFAULT 0,state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 delivery_state VARCHAR(30) NOT NULL DEFAULT 'NOT_RELEASED',fulfillment_state VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
 review_state VARCHAR(30) NOT NULL DEFAULT 'NOT_APPLICABLE',owner_id UUID NOT NULL,
 author_id UUID NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 authorized_at TIMESTAMPTZ,artifact_id UUID,artifact_hash VARCHAR(64),supersedes_id UUID,
 PRIMARY KEY(practice_id,id),UNIQUE(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id),
 FOREIGN KEY(practice_id,site_id) REFERENCES practice_site(practice_id,site_id),
 FOREIGN KEY(practice_id,patient_id,supersedes_id) REFERENCES referral(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TABLE referral_revision (
 practice_id UUID NOT NULL,id UUID NOT NULL,version BIGINT NOT NULL,
 destination VARCHAR(4000),clinical_question VARCHAR(4000),clinical_summary VARCHAR(4000),priority VARCHAR(4000),requested_date DATE,disclosure_reason VARCHAR(4000),
 state VARCHAR(24) NOT NULL,delivery_state VARCHAR(30) NOT NULL,fulfillment_state VARCHAR(30) NOT NULL,review_state VARCHAR(30) NOT NULL,
 owner_id UUID NOT NULL,artifact_id UUID,artifact_hash VARCHAR(64),reason VARCHAR(2000),recorded_by UUID NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,id,version),FOREIGN KEY(practice_id,id) REFERENCES referral(practice_id,id)
);
CREATE TRIGGER immutable_referral_revision BEFORE UPDATE OR DELETE ON referral_revision
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TABLE referral_preparation (
 practice_id UUID NOT NULL,id UUID NOT NULL,preparation_id UUID NOT NULL,source_version BIGINT NOT NULL,
 artifact_id UUID NOT NULL,artifact_hash VARCHAR(64) NOT NULL,prepared_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,preparation_id),FOREIGN KEY(practice_id,id) REFERENCES referral(practice_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TRIGGER immutable_referral_preparation BEFORE UPDATE OR DELETE ON referral_preparation
 FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

-- clinicalchart owns reviewed synthetic medication/allergy evidence.
CREATE TABLE medication_safety_review (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,review_id UUID NOT NULL,site_id UUID NOT NULL,
 allergy_state VARCHAR(24) NOT NULL CHECK(allergy_state IN ('UNKNOWN','KNOWN','NONE_KNOWN')),
 source VARCHAR(2000) NOT NULL,reviewed_by UUID NOT NULL,reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 interactions_unavailable_acknowledged BOOLEAN NOT NULL,
 PRIMARY KEY(practice_id,review_id),FOREIGN KEY(practice_id,patient_id) REFERENCES clinical_patient(practice_id,patient_id)
);
CREATE TABLE medication_safety_ingredient (
 practice_id UUID NOT NULL,review_id UUID NOT NULL,kind VARCHAR(20) NOT NULL CHECK(kind IN ('ALLERGY','MEDICATION')),
 ingredient VARCHAR(200) NOT NULL,PRIMARY KEY(practice_id,review_id,kind,ingredient),
 FOREIGN KEY(practice_id,review_id) REFERENCES medication_safety_review(practice_id,review_id)
);
CREATE TRIGGER immutable_medication_safety_review BEFORE UPDATE OR DELETE ON medication_safety_review FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TRIGGER immutable_medication_safety_ingredient BEFORE UPDATE OR DELETE ON medication_safety_ingredient FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
-- Explicit fictional sources for engineering validation; no clinical recommendation or approval.
CREATE TABLE prescribing_catalog (code VARCHAR(100) NOT NULL,version VARCHAR(100) NOT NULL,ingredient VARCHAR(200) NOT NULL,retired BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(code,version));
INSERT INTO prescribing_catalog(code,version,ingredient) VALUES('SYN-MED-A','synthetic-1','Synthetic ingredient A'),('SYN-MED-B','synthetic-1','Synthetic ingredient B');
CREATE TABLE service_request_catalog (code VARCHAR(100) NOT NULL,version VARCHAR(100) NOT NULL,kind VARCHAR(24) NOT NULL,label VARCHAR(200) NOT NULL,retired BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(code,version));
INSERT INTO service_request_catalog(code,version,kind,label) VALUES('SYN-LAB-A','synthetic-1','LAB_ORDER','Synthetic laboratory service A'),('SYN-LAB-B','synthetic-1','LAB_ORDER','Synthetic laboratory service B'),('SYN-IMG-A','synthetic-1','IMAGING_ORDER','Synthetic imaging service A');
-- Care coordination owns operational obligations. Source writes and obligation registration share a transaction.
CREATE TABLE intervention_owned_work (
 practice_id UUID NOT NULL,source_id UUID NOT NULL,kind VARCHAR(24) NOT NULL,patient_id UUID NOT NULL,encounter_id UUID NOT NULL,site_id UUID NOT NULL,
 owner_id UUID NOT NULL,proposed_owner_id UUID,source_version BIGINT NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 instruction VARCHAR(2000) NOT NULL,due_date DATE,state VARCHAR(24) NOT NULL DEFAULT 'OPEN',
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,kind,source_id),FOREIGN KEY(practice_id,patient_id) REFERENCES clinical_patient(practice_id,patient_id)
);
CREATE TABLE intervention_work_event (
 practice_id UUID NOT NULL,event_id UUID NOT NULL,source_id UUID NOT NULL,kind VARCHAR(24) NOT NULL,version BIGINT NOT NULL,
 actor_id UUID NOT NULL,action VARCHAR(50) NOT NULL,evidence VARCHAR(2000) NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,event_id),FOREIGN KEY(practice_id,kind,source_id) REFERENCES intervention_owned_work(practice_id,kind,source_id)
);
CREATE TRIGGER immutable_intervention_work_event BEFORE UPDATE OR DELETE ON intervention_work_event FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

CREATE TABLE service_request_evidence (
 practice_id UUID NOT NULL,id UUID NOT NULL,evidence_id UUID NOT NULL,source_version BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,outcome VARCHAR(30) NOT NULL,evidence VARCHAR(4000) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,recorded_by UUID NOT NULL,
 PRIMARY KEY(practice_id,evidence_id),FOREIGN KEY(practice_id,id) REFERENCES service_request(practice_id,id)
);
CREATE TRIGGER immutable_service_request_evidence BEFORE UPDATE OR DELETE ON service_request_evidence FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

CREATE TABLE referral_evidence (
 practice_id UUID NOT NULL,id UUID NOT NULL,evidence_id UUID NOT NULL,source_version BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,outcome VARCHAR(30) NOT NULL,evidence VARCHAR(4000) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,recorded_by UUID NOT NULL,
 PRIMARY KEY(practice_id,evidence_id),FOREIGN KEY(practice_id,id) REFERENCES referral(practice_id,id)
);
CREATE TRIGGER immutable_referral_evidence BEFORE UPDATE OR DELETE ON referral_evidence FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();

CREATE TABLE prescription_evidence (
 practice_id UUID NOT NULL,id UUID NOT NULL,evidence_id UUID NOT NULL,source_version BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,outcome VARCHAR(30) NOT NULL,evidence VARCHAR(4000) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,recorded_by UUID NOT NULL,
 PRIMARY KEY(practice_id,evidence_id),FOREIGN KEY(practice_id,id) REFERENCES prescription(practice_id,id)
);
CREATE TRIGGER immutable_prescription_evidence BEFORE UPDATE OR DELETE ON prescription_evidence FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
