package ph.epr.api.carecoordination.application;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import ph.epr.api.encountertemplates.EncounterInterventionLinks;
import ph.epr.api.encountertemplates.EncounterSigningDocumentIssuer;
import ph.epr.api.encountertemplates.EncounterInterventionLinks.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
import ph.epr.api.documentscommunication.PrivateClinicalArtifacts;
import ph.epr.api.documentscommunication.ClinicalHandoffDocuments;
import ph.epr.api.documentscommunication.ClinicalHandoffDocuments.Field;
import ph.epr.api.documentscommunication.ClinicalHandoffDocuments.Section;

/** Referral records and revisions belong exclusively to carecoordination. */
@Service
@Profile("local")
public class ReferralService implements EncounterSigningDocumentIssuer {
    private final EncounterInterventionLinks links;
    private final TenantCommandJournal journal;
    private final PrivateClinicalArtifacts artifacts;
    private final ClinicalHandoffDocuments documents;
    private final ph.epr.api.carecoordination.OwnedClinicalWork work;
    public ReferralService(EncounterInterventionLinks links,TenantCommandJournal journal,PrivateClinicalArtifacts artifacts, ph.epr.api.carecoordination.OwnedClinicalWork work, ClinicalHandoffDocuments documents) {
        this.links=links;this.journal=journal;this.artifacts=artifacts;this.work=work;this.documents=documents;
    }
    public record ReferralData(String destination, String clinicalQuestion, String clinicalSummary, String priority, LocalDate requestedDate, String disclosureReason) {}
    public record ReferralRecord(UUID id,UUID patientId,UUID encounterId,UUID siteId,long version,String state,
        String deliveryState,String fulfillmentState,String reviewState,UUID ownerId,UUID authorId,
        OffsetDateTime occurredAt,OffsetDateTime receivedAt,OffsetDateTime authorizedAt,UUID artifactId,String artifactHash,UUID supersedesId,ReferralData data) {}
    public record ReferralCreate(UUID id,UUID encounterId,OffsetDateTime occurredAt,ReferralData data,UUID supersedesId) {}
    public record ReferralUpdate(long expectedVersion,ReferralData data) {}
    public record ReferralPrepare(UUID preparationId,long expectedVersion) {}
    public record ReferralPreparation(UUID preparationId,long sourceVersion,UUID artifactId,String artifactHash) {}
    public record ReferralAuthorize(long expectedVersion,UUID preparationId,String reviewedArtifactHash,boolean confirmSyntheticReview,String lateActionReason,String duplicateReason) {}
    public record ReferralReason(long expectedVersion,String reason) {}
    public record ReferralEvidence(long expectedVersion,String outcome,String evidence,OffsetDateTime occurredAt) {}
    @Override public boolean supports(Kind kind) { return kind == Kind.REFERRAL; }
    @Override public void checkForSigning(Context c, UUID patient, UUID encounter, Item item) {
        var source = get(c, patient, item.summary().sourceId());
        if (item.summary().kind() != Kind.REFERRAL || !source.encounterId().equals(encounter)
            || !source.authorId().equals(c.actorId()) || !source.state().equals("DRAFT")
            || source.version() != item.summary().sourceVersion()) throw new ClinicalAccessException("SIGN_CANDIDATE_STALE", 409);
        candidateContent(c, patient, source.id(), new ReferralPrepare(UUID.randomUUID(), source.version()));
    }
    @Override public void issueWithSignature(Context c, UUID patient, UUID encounter, Item item, UUID signaturePreparationId, String duplicateIntentReason) {
        checkForSigning(c, patient, encounter, item);
        var source = get(c, patient, item.summary().sourceId());
        var kind = item.summary().kind();
        var preparationId = EncounterSigningDocumentIssuer.key(signaturePreparationId, kind, source.id(), "document");
        var prepared = prepare(c, patient, source.id(), EncounterSigningDocumentIssuer.key(signaturePreparationId, kind, source.id(), "prepare"),
            new ReferralPrepare(preparationId, source.version()));
        var lateReason = links.context(c, patient, encounter).careState().equals("IN_PROGRESS") ? null : "Released with the clinician's encounter signature after care was finished";
        authorize(c, patient, source.id(), EncounterSigningDocumentIssuer.key(signaturePreparationId, kind, source.id(), "issue"),
            new ReferralAuthorize(source.version(), prepared.preparationId(), prepared.artifactHash(), true, lateReason, duplicateIntentReason));
    }
    public List<ReferralRecord> list(Context c,UUID patient,int page) {
        if(page<0)throw new ClinicalAccessException("INVALID_PAGE",422);
        var ids=c.jdbc().sql("SELECT id FROM referral WHERE practice_id=:p AND patient_id=:patient AND site_id=:s AND author_id=:a ORDER BY received_at DESC,id LIMIT 30 OFFSET :offset")
            .param("p",c.practiceId()).param("patient",patient).param("s",c.siteId()).param("a",c.actorId()).param("offset",(long)page*30).query(UUID.class).list();
        return ids.stream().map(id->get(c,patient,id)).toList();
    }
    public ReferralRecord get(Context c,UUID patient,UUID id) {
        var record=load(c,patient,id);
        if(record.authorId().equals(c.actorId()))links.context(c,patient,record.encounterId());
        else {work.requireAcceptedAssignment(c,kind(record.data()).name(),record.id(),patient,record.encounterId());links.acceptedWorkContext(c,patient,record.encounterId());}
        links.require(c,kind(record.data()),InterventionAuthorization.Action.READ);
        return record;
    }
    private ReferralRecord load(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT * FROM referral WHERE practice_id=:p AND patient_id=:patient AND site_id=:site AND id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("site",c.siteId()).param("id",id)
            .query((r,n)->new ReferralRecord(id,patient,r.getObject("encounter_id",UUID.class),r.getObject("site_id",UUID.class),r.getLong("version"),r.getString("state"),
                r.getString("delivery_state"),r.getString("fulfillment_state"),r.getString("review_state"),r.getObject("owner_id",UUID.class),r.getObject("author_id",UUID.class),
                r.getObject("occurred_at",OffsetDateTime.class),r.getObject("received_at",OffsetDateTime.class),r.getObject("authorized_at",OffsetDateTime.class),
                r.getObject("artifact_id",UUID.class),r.getString("artifact_hash"),r.getObject("supersedes_id",UUID.class),data(c,id,r.getLong("version"))))
            .optional().orElseThrow(()->new ClinicalAccessException("REFERRAL_UNAVAILABLE",404));
    }
    private ReferralData data(Context c,UUID id,long version) {
        return c.jdbc().sql("SELECT * FROM referral_revision WHERE practice_id=:p AND id=:id AND version=:v")
            .param("p",c.practiceId()).param("id",id).param("v",version).query((r,n)->new ReferralData(r.getString("destination"), r.getString("clinical_question"), r.getString("clinical_summary"), r.getString("priority"), r.getObject("requested_date",LocalDate.class), r.getString("disclosure_reason"))).single();
    }
    public ReferralRecord create(Context c,UUID patient,UUID key,ReferralCreate request) {
        if(request.id()==null||request.encounterId()==null||request.occurredAt()==null||request.data()==null) fail("CONTEXT_REQUIRED");
        validate(request.data(),false);
        links.context(c,patient,request.encounterId());links.require(c,kind(request.data()),InterventionAuthorization.Action.PREPARE);
        return journal.execute(c,"create-referral/"+patient,key,request,ReferralRecord.class,()->{
            var encounter=links.lock(c,patient,request.encounterId());newDecision(encounter);
            if(request.supersedesId()!=null) {
                var original=get(c,patient,request.supersedesId());
                if(!original.state().equals("RELEASED") && !original.state().equals("CANCELLED")) fail("AUTHORIZED_ORIGINAL_REQUIRED");
            }
            if(c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM referral WHERE practice_id=:p AND id=:id)").param("p",c.practiceId()).param("id",request.id()).query(Boolean.class).single())throw new ClinicalAccessException("SOURCE_ID_ALREADY_USED",409);
            c.jdbc().sql("INSERT INTO referral(practice_id,patient_id,encounter_id,site_id,id,owner_id,author_id,occurred_at,supersedes_id) VALUES(:p,:patient,:e,:site,:id,:a,:a,:time,:original)")
                .param("p",c.practiceId()).param("patient",patient).param("e",request.encounterId()).param("site",c.siteId()).param("id",request.id()).param("a",c.actorId())
                .param("time",request.occurredAt()).param("original",request.supersedesId(),java.sql.Types.OTHER).update();
            revision(c,request.id(),0,request.data(),null);
            var result=get(c,patient,request.id());publish(c,result);journal.audit(c,result.id(),"REFERRAL_DRAFT_CREATED",null);return result;
        });
    }
    public ReferralRecord update(Context c,UUID patient,UUID id,UUID key,ReferralUpdate request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.PREPARE);validate(request.data(),false);
        return journal.execute(c,"update-referral/"+id,key,request,ReferralRecord.class,()->{
            var encounter=lockSource(c,patient,before);newDecision(encounter);
            var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);
            if(kind(current.data())!=kind(request.data())) fail("KIND_IMMUTABLE");
            bump(c,id);revision(c,id,current.version()+1,request.data(),null);
            var result=get(c,patient,id);publish(c,result);journal.audit(c,id,"REFERRAL_DRAFT_UPDATED",null);return result;
        });
    }
    public byte[] candidateContent(Context c,UUID patient,UUID id,ReferralPrepare request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        var encounter=lockSource(c,patient,before);unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);validate(current.data(),true);prerequisites(c,current);
        return render(c,current);
    }
    public ReferralPreparation prepare(Context c,UUID patient,UUID id,UUID key,ReferralPrepare request) {
        return prepare(c,patient,id,key,request,null);
    }
    public ReferralPreparation prepare(Context c,UUID patient,UUID id,UUID key,ReferralPrepare request,PrivateClinicalArtifacts.Artifact staged) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        return journal.execute(c,"prepare-referral/"+id,key,request,ReferralPreparation.class,()->{
            var encounter=lockSource(c,patient,before);unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);validate(current.data(),true);prerequisites(c,current);
            if(request.preparationId()==null) fail("PREPARATION_ID_REQUIRED");
            var artifact=staged==null?artifacts.stage(c,request.preparationId(),render(c,current)):staged;
            if(!artifact.artifactId().equals(request.preparationId()))fail("ARTIFACT_CONTEXT_MISMATCH");
            if(staged!=null) { if(!PrivateClinicalArtifacts.sha256(render(c,current)).equals(staged.sha256())) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409); artifacts.attach(c,staged); }
            c.jdbc().sql("INSERT INTO referral_preparation(practice_id,id,preparation_id,source_version,artifact_id,artifact_hash,prepared_by) VALUES(:p,:id,:candidate,:v,:artifact,:hash,:a)")
                .param("p",c.practiceId()).param("id",id).param("candidate",request.preparationId()).param("v",current.version()).param("artifact",artifact.artifactId()).param("hash",artifact.sha256()).param("a",c.actorId()).update();
            return new ReferralPreparation(request.preparationId(),current.version(),artifact.artifactId(),artifact.sha256());
        });
    }
    public ReferralRecord authorize(Context c,UUID patient,UUID id,UUID key,ReferralAuthorize request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        return journal.execute(c,"authorize-referral/"+id,key,request,ReferralRecord.class,()->{
            var original=before.supersedesId()==null?null:get(c,patient,before.supersedesId());
            var boundaries=new java.util.TreeSet<UUID>();boundaries.add(before.encounterId());if(original!=null)boundaries.add(original.encounterId());
            for(var boundary:boundaries)links.lock(c,patient,boundary);
            var encounter=links.context(c,patient,before.encounterId());unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);
            if(!request.confirmSyntheticReview()) fail("DELIBERATE_SYNTHETIC_REVIEW_REQUIRED");
            if(!encounter.careState().equals("IN_PROGRESS")) required(request.lateActionReason(),"LATE_ACTION_REASON_REQUIRED");
            validate(current.data(),true);prerequisites(c,current);var repeated=duplicates(c,current,request.duplicateReason());
            var prep=c.jdbc().sql("SELECT preparation_id,source_version,artifact_id,artifact_hash FROM referral_preparation WHERE practice_id=:p AND id=:id AND preparation_id=:candidate AND prepared_by=:a")
                .param("p",c.practiceId()).param("id",id).param("candidate",request.preparationId()).param("a",c.actorId())
                .query((r,n)->new ReferralPreparation(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class),r.getString(4)))
                .optional().orElseThrow(()->new ClinicalAccessException("REVIEW_CANDIDATE_REQUIRED",422));
            if(prep.sourceVersion()!=current.version()||!prep.artifactHash().equals(request.reviewedArtifactHash())) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409);
            artifacts.archive(c,prep.artifactId(),prep.artifactHash());
            c.jdbc().sql("UPDATE referral SET state='RELEASED',version=version+1,authorized_at=CURRENT_TIMESTAMP,artifact_id=:artifact,artifact_hash=:hash,delivery_state='NOT_SENT' WHERE practice_id=:p AND id=:id")
                .param("artifact",prep.artifactId()).param("hash",prep.artifactHash()).param("p",c.practiceId()).param("id",id).update();
            var recordedReason=request.lateActionReason()!=null?request.lateActionReason():repeated?request.duplicateReason():null;
            revision(c,id,current.version()+1,current.data(),recordedReason);
            var result=get(c,patient,id);publish(c,result);obligation(c,result);
            if(original!=null) {
                var previous=get(c,patient,original.id());
                if(!Set.of("RELEASED","CANCELLED").contains(previous.state()))throw new ClinicalAccessException("ORIGINAL_SUPERSESSION_CONFLICT",409);
                if(!kind(previous.data()).equals(kind(result.data())))fail("SUPERSESSION_KIND_MISMATCH");
                if(previous.state().equals("RELEASED")) {
                    c.jdbc().sql("UPDATE referral SET state='SUPERSEDED',version=version+1 WHERE practice_id=:p AND id=:original").param("p",c.practiceId()).param("original",previous.id()).update();
                    revision(c,previous.id(),previous.version()+1,previous.data(),"Superseded by "+result.id());publish(c,get(c,patient,previous.id()));
                    journal.audit(c,previous.id(),"REFERRAL_SUPERSEDED","Replacement "+result.id());
                }
            }journal.audit(c,id,"SYNTHETIC_RELEASED",recordedReason);journal.event(c,id,"RELEASED/"+result.version(),result.ownerId());return result;
        });
    }
    public ReferralRecord abandon(Context c,UUID patient,UUID id,UUID key,ReferralReason request) { return end(c,patient,id,key,request,true); }
    public ReferralRecord cancel(Context c,UUID patient,UUID id,UUID key,ReferralReason request) { return end(c,patient,id,key,request,false); }
    private ReferralRecord end(Context c,UUID patient,UUID id,UUID key,ReferralReason request,boolean abandon) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),abandon?InterventionAuthorization.Action.PREPARE:InterventionAuthorization.Action.CONTINUE_EXISTING);required(request.reason(),"REASON_REQUIRED");
        return journal.execute(c,(abandon?"abandon-":"cancel-")+"referral/"+id,key,request,ReferralRecord.class,()->{
            var encounter=lockSource(c,patient,before);if(abandon)unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(abandon) draft(current);else if(!current.state().equals("RELEASED")) fail("AUTHORIZED_RECORD_REQUIRED");
            if(!abandon && current.fulfillmentState().equals("FULFILLED")) fail("PERFORMED_CARE_CANNOT_BE_CANCELLED");
            c.jdbc().sql("UPDATE referral SET state=:state,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("state",abandon?"ABANDONED":"CANCELLED").param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.reason());var result=get(c,patient,id);publish(c,result);
            if(!abandon)work.register(c,new ph.epr.api.carecoordination.OwnedClinicalWork.Obligation(result.id(),kind(result.data()).name(),patient,result.encounterId(),result.siteId(),result.ownerId(),result.version(),"Confirm cancellation notice and disposition: "+request.reason(),result.data().requestedDate()));
            journal.audit(c,id,abandon?"DRAFT_ABANDONED":"REFERRAL_CANCELLED",request.reason());return result;
        });
    }
    public byte[] artifact(Context c,UUID patient,UUID id,UUID preparationId) {
        var current=get(c,patient,id);UUID artifact=current.artifactId();String hash=current.artifactHash();
        if(preparationId!=null) {
            links.require(c,kind(current.data()),InterventionAuthorization.Action.AUTHORIZE);
            var prep=c.jdbc().sql("SELECT source_version,artifact_id,artifact_hash FROM referral_preparation WHERE practice_id=:p AND id=:id AND preparation_id=:candidate AND prepared_by=:a")
                .param("p",c.practiceId()).param("id",id).param("candidate",preparationId).param("a",c.actorId())
                .query((r,n)->new ReferralPreparation(preparationId,r.getLong(1),r.getObject(2,UUID.class),r.getString(3)))
                .optional().orElseThrow(()->new ClinicalAccessException("ARTIFACT_UNAVAILABLE",404));
            if(prep.sourceVersion()!=current.version()) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409);
            artifact=prep.artifactId();hash=prep.artifactHash();
        }
        if(artifact==null) throw new ClinicalAccessException("AUTHORIZED_ARTIFACT_UNAVAILABLE",404);
        journal.audit(c,id,"REFERRAL_ARTIFACT_READ",null);return artifacts.readVerified(c,artifact,hash);
    }
    public List<ReferralRecord> history(Context c,UUID patient,UUID id) {
        var current=get(c,patient,id);
        return c.jdbc().sql("SELECT version,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,received_at FROM referral_revision WHERE practice_id=:p AND id=:id ORDER BY version")
            .param("p",c.practiceId()).param("id",id).query((r,n)->new ReferralRecord(id,patient,current.encounterId(),current.siteId(),r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class),current.authorId(),current.occurredAt(),r.getObject(9,OffsetDateTime.class),Set.of("DRAFT","ABANDONED").contains(r.getString(2))?null:current.authorizedAt(),r.getObject(7,UUID.class),r.getString(8),current.supersedesId(),data(c,id,r.getLong(1)))).list();
    }
    private void revision(Context c,UUID id,long version,ReferralData data,String reason) {
        c.jdbc().sql("INSERT INTO referral_revision(practice_id,id,version,destination,clinical_question,clinical_summary,priority,requested_date,disclosure_reason,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,reason,recorded_by) SELECT practice_id,id,:v,:destination,:clinicalQuestion,:clinicalSummary,:priority,:requestedDate,:disclosureReason,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,:reason,:a FROM referral WHERE practice_id=:p AND id=:id")
            .param("p",c.practiceId()).param("id",id).param("v",version).param("destination",data.destination(),java.sql.Types.VARCHAR).param("clinicalQuestion",data.clinicalQuestion(),java.sql.Types.VARCHAR).param("clinicalSummary",data.clinicalSummary(),java.sql.Types.VARCHAR).param("priority",data.priority(),java.sql.Types.VARCHAR).param("requestedDate",data.requestedDate(),java.sql.Types.DATE).param("disclosureReason",data.disclosureReason(),java.sql.Types.VARCHAR).param("reason",reason,java.sql.Types.VARCHAR).param("a",c.actorId()).update();
        saveItems(c,id,version,data);
    }
    private void publish(Context c,ReferralRecord record) {
        var data=record.data();
        links.append(c,record.patientId(),record.encounterId(),new Summary(kind(data),record.id(),record.version(),"Referral to " + data.destination(),List.of(
            "Clinical question: "+text(data.clinicalQuestion()),"Selected summary: "+text(data.clinicalSummary()),
            "Priority: "+text(data.priority()),"Intended date: "+(data.requestedDate()==null?"":data.requestedDate()),
            "Disclosure review: "+text(data.disclosureReason())),
            record.state(),record.deliveryState(),record.fulfillmentState(),record.reviewState(),record.ownerId(),data.requestedDate(),record.authorizedAt()==null?record.occurredAt():record.authorizedAt(),record.artifactId(),record.artifactHash()));
    }
    private byte[] render(Context c,ReferralRecord record) {
        var encounter=record.authorId().equals(c.actorId())?links.context(c,record.patientId(),record.encounterId()):links.acceptedWorkContext(c,record.patientId(),record.encounterId());var data=record.data();
        return documents.render(c,record.authorId(),"Referral letter",encounter.patientName(),encounter.recordNumber(),record.id(),List.of(
            new Section("Receiving service",List.of(new Field("Doctor / service / facility",data.destination()),new Field("Priority",data.priority()),
                new Field("Intended date",data.requestedDate()==null?null:data.requestedDate().toString()),
                new Field("Referral recorded",ClinicalHandoffDocuments.clinicalTime(record.occurredAt())))),
            new Section("Clinical information selected for referral",List.of(new Field("Clinical question",data.clinicalQuestion()),
                new Field("Clinical summary",data.clinicalSummary())))));
    }
    private Kind kind(ReferralData data) { return Kind.REFERRAL; }
    private void bump(Context c,UUID id) { c.jdbc().sql("UPDATE referral SET version=version+1 WHERE practice_id=:p AND id=:id").param("p",c.practiceId()).param("id",id).update(); }
    private void newDecision(EncounterContext encounter) { if(!encounter.careState().equals("IN_PROGRESS")||encounter.noteState().equals("SIGNED")) throw new ClinicalAccessException("NEW_FOLLOW_UP_ENCOUNTER_REQUIRED",409); }
    private void unsigned(EncounterContext encounter) { if("SIGNED".equals(encounter.noteState())) throw new ClinicalAccessException("SIGNED_NOTE_IMMUTABLE",409); }
    private void version(ReferralRecord r,long expected) { if(r.version()!=expected) throw new ClinicalAccessException("VERSION_CONFLICT",409); }
    private void draft(ReferralRecord r) { if(!r.state().equals("DRAFT")) throw new ClinicalAccessException("AUTHORIZED_RECORD_IMMUTABLE",409); }
    private static String text(String s) { return s==null?"":s; }
    private static String escape(String s) { return text(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private static void required(String s,String code) { if(s==null||s.isBlank()||s.length()>4000) fail(code); }
    private static void fail(String code) { throw new ClinicalAccessException(code,422); }
    private void saveItems(Context c,UUID id,long version,ReferralData data) {}
    private void obligation(Context c,ReferralRecord record) {work.register(c,new ph.epr.api.carecoordination.OwnedClinicalWork.Obligation(record.id(),kind(record.data()).name(),record.patientId(),record.encounterId(),record.siteId(),record.ownerId(),record.version(),"Review outcome for "+record.data().destination(),record.data().requestedDate()));}
    private void prerequisites(Context c,ReferralRecord record) {  }
private void validate(ReferralData data,boolean release){
        if(data==null)fail("REFERRAL_DETAILS_REQUIRED");required(data.destination(),"DESTINATION_REQUIRED");
        if(release){required(data.clinicalQuestion(),"CLINICAL_QUESTION_REQUIRED");required(data.clinicalSummary(),"SELECTED_SUMMARY_REQUIRED");required(data.disclosureReason(),"DISCLOSURE_REVIEW_REQUIRED");required(data.priority(),"PRIORITY_REQUIRED");if(data.requestedDate()==null)fail("REQUESTED_DATE_REQUIRED");}
    }
    private boolean duplicates(Context c,ReferralRecord record,String reason){
        var repeated=c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM referral s JOIN referral_revision r ON r.practice_id=s.practice_id AND r.id=s.id AND r.version=s.version WHERE s.practice_id=:p AND s.patient_id=:patient AND s.id<>:id AND s.state='RELEASED' AND s.review_state<>'REVIEWED' AND r.destination=:destination)").param("p",c.practiceId()).param("patient",record.patientId()).param("id",record.id()).param("destination",record.data().destination()).query(Boolean.class).single();
        if(repeated)required(reason,"DUPLICATE_INTENT_REASON_REQUIRED");
        return repeated;
    }


    public ReferralRecord delivery(Context c,UUID patient,UUID id,UUID key,ReferralEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("ACKNOWLEDGED","DELIVERED","FAILED","PRINTED","UNKNOWN").contains(request.outcome()))fail("INVALID_DELIVERY_EVIDENCE");
        return journal.execute(c,"referral-delivery/"+id,key,request,ReferralRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");
            c.jdbc().sql("INSERT INTO referral_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'DELIVERY',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE referral SET delivery_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);
            journal.audit(c,id,"REFERRAL_DELIVERY_RECORDED",null);return result;
        });
    }

    public ReferralRecord outcome(Context c,UUID patient,UUID id,UUID key,ReferralEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("ACCEPTED","ATTENDED","DECLINED","PATIENT_REFUSED","UNABLE").contains(request.outcome()))fail("INVALID_OUTCOME_EVIDENCE");
        return journal.execute(c,"referral-outcome/"+id,key,request,ReferralRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");
            c.jdbc().sql("INSERT INTO referral_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'OUTCOME',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE referral SET fulfillment_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);obligation(c,result);
            
            journal.audit(c,id,"REFERRAL_OUTCOME_RECORDED",null);return result;
        });
    }

    public ReferralRecord review(Context c,UUID patient,UUID id,UUID key,ReferralEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("REVIEWED").contains(request.outcome()))fail("INVALID_REVIEW_EVIDENCE");
        return journal.execute(c,"referral-review/"+id,key,request,ReferralRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");if(current.state().equals("DRAFT")||current.state().equals("ABANDONED"))fail("RELEASED_SOURCE_REQUIRED");if(current.fulfillmentState().equals("NOT_STARTED"))fail("REFERRAL_OUTCOME_REQUIRED");
            c.jdbc().sql("INSERT INTO referral_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'REVIEW',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE referral SET review_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);work.reviewed(c,kind(result.data()).name(),id,result.version(),request.evidence());
            journal.audit(c,id,"REFERRAL_REVIEW_RECORDED",null);return result;
        });
    }
    private EncounterContext lockSource(Context c,UUID patient,ReferralRecord source) {
        if(source.authorId().equals(c.actorId()))return links.lock(c,patient,source.encounterId());
        work.requireAcceptedAssignment(c,kind(source.data()).name(),source.id(),patient,source.encounterId());
        return links.lockAcceptedWork(c,patient,source.encounterId());
    }
}
