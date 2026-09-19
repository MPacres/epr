-- ordersresults owns immutable requisition membership and archived composite print snapshots.
CREATE TABLE requisition_group (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,encounter_id UUID NOT NULL,site_id UUID NOT NULL,id UUID NOT NULL,
 created_by UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,id),UNIQUE(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,patient_id,encounter_id) REFERENCES clinical_encounter(practice_id,patient_id,encounter_id),
 FOREIGN KEY(practice_id,site_id) REFERENCES practice_site(practice_id,site_id)
);
CREATE TABLE requisition_group_member (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,group_id UUID NOT NULL,request_id UUID NOT NULL,
 PRIMARY KEY(practice_id,group_id,request_id),UNIQUE(practice_id,request_id),
 FOREIGN KEY(practice_id,patient_id,group_id) REFERENCES requisition_group(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,patient_id,request_id) REFERENCES service_request(practice_id,patient_id,id)
);
CREATE TRIGGER immutable_requisition_membership BEFORE UPDATE OR DELETE ON requisition_group_member FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE TABLE requisition_group_document (
 practice_id UUID NOT NULL,patient_id UUID NOT NULL,group_id UUID NOT NULL,document_id UUID NOT NULL,
 members JSONB NOT NULL,artifact_id UUID NOT NULL,artifact_hash VARCHAR(64) NOT NULL,created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,document_id),FOREIGN KEY(practice_id,patient_id,group_id) REFERENCES requisition_group(practice_id,patient_id,id),
 FOREIGN KEY(practice_id,artifact_id) REFERENCES private_clinical_artifact(practice_id,artifact_id)
);
CREATE TRIGGER immutable_requisition_document BEFORE UPDATE OR DELETE ON requisition_group_document FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
-- clinicalchart: explicit monotonic evidence ordering prevents use of a superseded safety review.
ALTER TABLE medication_safety_review ADD COLUMN review_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
