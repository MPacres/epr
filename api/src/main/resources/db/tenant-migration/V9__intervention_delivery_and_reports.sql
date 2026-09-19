-- carecoordination: durable delivery retries, independent of browser activity.
CREATE TABLE clinical_delivery_checkpoint (
 practice_id UUID NOT NULL,site_id UUID NOT NULL,actor_id UUID NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_success_at TIMESTAMPTZ,last_error_code VARCHAR(100),
 PRIMARY KEY(practice_id,site_id,actor_id),
 FOREIGN KEY(practice_id,site_id) REFERENCES practice_site(practice_id,site_id)
);
-- ordersresults: reports stay quarantined until an explicit, versioned match.
CREATE TABLE incoming_service_report (
 practice_id UUID NOT NULL,site_id UUID NOT NULL,id UUID NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('LAB_ORDER','IMAGING_ORDER')),source VARCHAR(1000) NOT NULL,external_reference VARCHAR(300) NOT NULL,content VARCHAR(10000) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 received_by UUID NOT NULL,corrects_id UUID,patient_id UUID,request_id UUID,
 state VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED' CHECK(state IN ('UNMATCHED','MATCHED')),
 PRIMARY KEY(practice_id,id),FOREIGN KEY(practice_id,site_id) REFERENCES practice_site(practice_id,site_id),
 FOREIGN KEY(practice_id,corrects_id) REFERENCES incoming_service_report(practice_id,id),
 FOREIGN KEY(practice_id,patient_id,request_id) REFERENCES service_request(practice_id,patient_id,id),
 CHECK((state='UNMATCHED' AND patient_id IS NULL AND request_id IS NULL) OR (state='MATCHED' AND patient_id IS NOT NULL AND request_id IS NOT NULL))
);
CREATE TABLE service_report_match_event (
 practice_id UUID NOT NULL,report_id UUID NOT NULL,patient_id UUID NOT NULL,request_id UUID NOT NULL,
 reason VARCHAR(2000) NOT NULL,matched_by UUID NOT NULL,matched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(practice_id,report_id),FOREIGN KEY(practice_id,report_id) REFERENCES incoming_service_report(practice_id,id),
 FOREIGN KEY(practice_id,patient_id,request_id) REFERENCES service_request(practice_id,patient_id,id)
);
CREATE TRIGGER immutable_report_match BEFORE UPDATE OR DELETE ON service_report_match_event FOR EACH ROW EXECUTE FUNCTION reject_clinical_history_mutation();
CREATE FUNCTION protect_incoming_report_content() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR NEW.source IS DISTINCT FROM OLD.source OR NEW.external_reference IS DISTINCT FROM OLD.external_reference
 OR NEW.content IS DISTINCT FROM OLD.content OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at OR NEW.corrects_id IS DISTINCT FROM OLD.corrects_id
 OR OLD.state='MATCHED' THEN RAISE EXCEPTION 'Original report content and matched identity are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_report_content BEFORE UPDATE OR DELETE ON incoming_service_report FOR EACH ROW EXECUTE FUNCTION protect_incoming_report_content();
