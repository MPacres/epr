package ph.epr.api.ordersresults.application;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import ph.epr.api.encountertemplates.EncounterInterventionLinks;
import ph.epr.api.encountertemplates.EncounterInterventionLinks.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
import ph.epr.api.documentscommunication.PrivateClinicalArtifacts;

/** ServiceRequest records and revisions belong exclusively to ordersresults. */
@Service
@Profile("local")
public class ServiceRequestService {
    private final EncounterInterventionLinks links;
    private final TenantCommandJournal journal;
    private final PrivateClinicalArtifacts artifacts;
    private final ph.epr.api.carecoordination.OwnedClinicalWork work;
    public ServiceRequestService(EncounterInterventionLinks links,TenantCommandJournal journal,PrivateClinicalArtifacts artifacts, ph.epr.api.carecoordination.OwnedClinicalWork work) {
        this.links=links;this.journal=journal;this.artifacts=artifacts;this.work=work;
    }
    public record ServiceRequestData(String kind, UUID groupId, String catalogCode, String catalogVersion, String serviceName, String indication, String priority, String destination, String specimen, String bodySite, String laterality, String instructions, LocalDate requestedDate) {}
    public record ServiceRequestRecord(UUID id,UUID patientId,UUID encounterId,UUID siteId,long version,String state,
        String deliveryState,String fulfillmentState,String reviewState,UUID ownerId,UUID authorId,
        OffsetDateTime occurredAt,OffsetDateTime receivedAt,OffsetDateTime authorizedAt,UUID artifactId,String artifactHash,UUID supersedesId,ServiceRequestData data) {}
    public record ServiceRequestCreate(UUID id,UUID encounterId,OffsetDateTime occurredAt,ServiceRequestData data,UUID supersedesId) {}
    public record ServiceRequestUpdate(long expectedVersion,ServiceRequestData data) {}
    public record ServiceRequestPrepare(UUID preparationId,long expectedVersion) {}
    public record ServiceRequestPreparation(UUID preparationId,long sourceVersion,UUID artifactId,String artifactHash) {}
    public record ServiceRequestAuthorize(long expectedVersion,UUID preparationId,String reviewedArtifactHash,boolean confirmSyntheticReview,String lateActionReason,String duplicateReason) {}
    public record ServiceRequestReason(long expectedVersion,String reason) {}
    public record ServiceRequestEvidence(long expectedVersion,String outcome,String evidence,OffsetDateTime occurredAt) {}
    public List<ServiceRequestRecord> list(Context c,UUID patient,int page) {
        if(page<0)throw new ClinicalAccessException("INVALID_PAGE",422);
        var ids=c.jdbc().sql("SELECT id FROM service_request WHERE practice_id=:p AND patient_id=:patient AND site_id=:s AND author_id=:a ORDER BY received_at DESC,id LIMIT 30 OFFSET :offset")
            .param("p",c.practiceId()).param("patient",patient).param("s",c.siteId()).param("a",c.actorId()).param("offset",(long)page*30).query(UUID.class).list();
        return ids.stream().map(id->get(c,patient,id)).toList();
    }
    public ServiceRequestRecord get(Context c,UUID patient,UUID id) {
        var record=load(c,patient,id);
        if(record.authorId().equals(c.actorId()))links.context(c,patient,record.encounterId());
        else {work.requireAcceptedAssignment(c,kind(record.data()).name(),record.id(),patient,record.encounterId());links.acceptedWorkContext(c,patient,record.encounterId());}
        links.require(c,kind(record.data()),InterventionAuthorization.Action.READ);
        return record;
    }
    private ServiceRequestRecord load(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT * FROM service_request WHERE practice_id=:p AND patient_id=:patient AND site_id=:site AND id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("site",c.siteId()).param("id",id)
            .query((r,n)->new ServiceRequestRecord(id,patient,r.getObject("encounter_id",UUID.class),r.getObject("site_id",UUID.class),r.getLong("version"),r.getString("state"),
                r.getString("delivery_state"),r.getString("fulfillment_state"),r.getString("review_state"),r.getObject("owner_id",UUID.class),r.getObject("author_id",UUID.class),
                r.getObject("occurred_at",OffsetDateTime.class),r.getObject("received_at",OffsetDateTime.class),r.getObject("authorized_at",OffsetDateTime.class),
                r.getObject("artifact_id",UUID.class),r.getString("artifact_hash"),r.getObject("supersedes_id",UUID.class),data(c,id,r.getLong("version"))))
            .optional().orElseThrow(()->new ClinicalAccessException("SERVICEREQUEST_UNAVAILABLE",404));
    }
    private ServiceRequestData data(Context c,UUID id,long version) {
        return c.jdbc().sql("SELECT * FROM service_request_revision WHERE practice_id=:p AND id=:id AND version=:v")
            .param("p",c.practiceId()).param("id",id).param("v",version).query((r,n)->new ServiceRequestData(r.getString("kind"), r.getObject("group_id",UUID.class), r.getString("catalog_code"), r.getString("catalog_version"), r.getString("service_name"), r.getString("indication"), r.getString("priority"), r.getString("destination"), r.getString("specimen"), r.getString("body_site"), r.getString("laterality"), r.getString("instructions"), r.getObject("requested_date",LocalDate.class))).single();
    }
    public ServiceRequestRecord create(Context c,UUID patient,UUID key,ServiceRequestCreate request) {
        if(request.id()==null||request.encounterId()==null||request.occurredAt()==null||request.data()==null) fail("CONTEXT_REQUIRED");
        validate(request.data(),false);
        links.context(c,patient,request.encounterId());links.require(c,kind(request.data()),InterventionAuthorization.Action.PREPARE);
        return journal.execute(c,"create-service_request/"+patient,key,request,ServiceRequestRecord.class,()->{
            var encounter=links.lock(c,patient,request.encounterId());newDecision(encounter);
            if(request.data().groupId()!=null && !c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM requisition_group WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e AND id=:g AND created_by=:a)").param("p",c.practiceId()).param("patient",patient).param("e",request.encounterId()).param("g",request.data().groupId()).param("a",c.actorId()).query(Boolean.class).single())fail("REQUISITION_GROUP_REQUIRED");
            if(request.supersedesId()!=null) {
                var original=get(c,patient,request.supersedesId());
                if(!original.state().equals("AUTHORIZED") && !original.state().equals("CANCELLED")) fail("AUTHORIZED_ORIGINAL_REQUIRED");
            }
            if(c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM service_request WHERE practice_id=:p AND id=:id)").param("p",c.practiceId()).param("id",request.id()).query(Boolean.class).single())throw new ClinicalAccessException("SOURCE_ID_ALREADY_USED",409);
            c.jdbc().sql("INSERT INTO service_request(practice_id,patient_id,encounter_id,site_id,id,owner_id,author_id,occurred_at,supersedes_id) VALUES(:p,:patient,:e,:site,:id,:a,:a,:time,:original)")
                .param("p",c.practiceId()).param("patient",patient).param("e",request.encounterId()).param("site",c.siteId()).param("id",request.id()).param("a",c.actorId())
                .param("time",request.occurredAt()).param("original",request.supersedesId(),java.sql.Types.OTHER).update();
            revision(c,request.id(),0,request.data(),null);
            if(request.data().groupId()!=null)c.jdbc().sql("INSERT INTO requisition_group_member(practice_id,patient_id,group_id,request_id) VALUES(:p,:patient,:g,:id)").param("p",c.practiceId()).param("patient",patient).param("g",request.data().groupId()).param("id",request.id()).update();
            var result=get(c,patient,request.id());publish(c,result);journal.audit(c,result.id(),"SERVICEREQUEST_DRAFT_CREATED",null);return result;
        });
    }
    public ServiceRequestRecord update(Context c,UUID patient,UUID id,UUID key,ServiceRequestUpdate request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.PREPARE);validate(request.data(),false);
        return journal.execute(c,"update-service_request/"+id,key,request,ServiceRequestRecord.class,()->{
            var encounter=lockSource(c,patient,before);newDecision(encounter);
            var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);
            if(kind(current.data())!=kind(request.data())) fail("KIND_IMMUTABLE");
            if(!Objects.equals(current.data().groupId(),request.data().groupId()))fail("GROUP_MEMBERSHIP_IMMUTABLE");
            bump(c,id);revision(c,id,current.version()+1,request.data(),null);
            var result=get(c,patient,id);publish(c,result);journal.audit(c,id,"SERVICEREQUEST_DRAFT_UPDATED",null);return result;
        });
    }
    public byte[] candidateContent(Context c,UUID patient,UUID id,ServiceRequestPrepare request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        var encounter=lockSource(c,patient,before);unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);validate(current.data(),true);prerequisites(c,current);
        return render(c,current);
    }
    public ServiceRequestPreparation prepare(Context c,UUID patient,UUID id,UUID key,ServiceRequestPrepare request) {
        return prepare(c,patient,id,key,request,null);
    }
    public ServiceRequestPreparation prepare(Context c,UUID patient,UUID id,UUID key,ServiceRequestPrepare request,PrivateClinicalArtifacts.Artifact staged) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        return journal.execute(c,"prepare-service_request/"+id,key,request,ServiceRequestPreparation.class,()->{
            var encounter=lockSource(c,patient,before);unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);validate(current.data(),true);prerequisites(c,current);
            if(request.preparationId()==null) fail("PREPARATION_ID_REQUIRED");
            var artifact=staged==null?artifacts.stage(c,request.preparationId(),render(c,current)):staged;
            if(!artifact.artifactId().equals(request.preparationId()))fail("ARTIFACT_CONTEXT_MISMATCH");
            if(staged!=null) { if(!PrivateClinicalArtifacts.sha256(render(c,current)).equals(staged.sha256())) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409); artifacts.attach(c,staged); }
            c.jdbc().sql("INSERT INTO service_request_preparation(practice_id,id,preparation_id,source_version,artifact_id,artifact_hash,prepared_by) VALUES(:p,:id,:candidate,:v,:artifact,:hash,:a)")
                .param("p",c.practiceId()).param("id",id).param("candidate",request.preparationId()).param("v",current.version()).param("artifact",artifact.artifactId()).param("hash",artifact.sha256()).param("a",c.actorId()).update();
            return new ServiceRequestPreparation(request.preparationId(),current.version(),artifact.artifactId(),artifact.sha256());
        });
    }
    public ServiceRequestRecord authorize(Context c,UUID patient,UUID id,UUID key,ServiceRequestAuthorize request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        return journal.execute(c,"authorize-service_request/"+id,key,request,ServiceRequestRecord.class,()->{
            var original=before.supersedesId()==null?null:get(c,patient,before.supersedesId());
            var boundaries=new java.util.TreeSet<UUID>();boundaries.add(before.encounterId());if(original!=null)boundaries.add(original.encounterId());
            for(var boundary:boundaries)links.lock(c,patient,boundary);
            var encounter=links.context(c,patient,before.encounterId());unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);
            if(!request.confirmSyntheticReview()) fail("DELIBERATE_SYNTHETIC_REVIEW_REQUIRED");
            if(!encounter.careState().equals("IN_PROGRESS")) required(request.lateActionReason(),"LATE_ACTION_REASON_REQUIRED");
            validate(current.data(),true);prerequisites(c,current);duplicates(c,current,request.duplicateReason());
            var prep=c.jdbc().sql("SELECT preparation_id,source_version,artifact_id,artifact_hash FROM service_request_preparation WHERE practice_id=:p AND id=:id AND preparation_id=:candidate AND prepared_by=:a")
                .param("p",c.practiceId()).param("id",id).param("candidate",request.preparationId()).param("a",c.actorId())
                .query((r,n)->new ServiceRequestPreparation(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class),r.getString(4)))
                .optional().orElseThrow(()->new ClinicalAccessException("REVIEW_CANDIDATE_REQUIRED",422));
            if(prep.sourceVersion()!=current.version()||!prep.artifactHash().equals(request.reviewedArtifactHash())) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409);
            artifacts.archive(c,prep.artifactId(),prep.artifactHash());
            c.jdbc().sql("UPDATE service_request SET state='AUTHORIZED',version=version+1,authorized_at=CURRENT_TIMESTAMP,artifact_id=:artifact,artifact_hash=:hash,delivery_state='NOT_SENT' WHERE practice_id=:p AND id=:id")
                .param("artifact",prep.artifactId()).param("hash",prep.artifactHash()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.lateActionReason());
            var result=get(c,patient,id);publish(c,result);obligation(c,result);
            if(original!=null) {
                var previous=get(c,patient,original.id());
                if(!Set.of("AUTHORIZED","CANCELLED").contains(previous.state()))throw new ClinicalAccessException("ORIGINAL_SUPERSESSION_CONFLICT",409);
                if(!kind(previous.data()).equals(kind(result.data())))fail("SUPERSESSION_KIND_MISMATCH");
                if(previous.state().equals("AUTHORIZED")) {
                    c.jdbc().sql("UPDATE service_request SET state='SUPERSEDED',version=version+1 WHERE practice_id=:p AND id=:original").param("p",c.practiceId()).param("original",previous.id()).update();
                    revision(c,previous.id(),previous.version()+1,previous.data(),"Superseded by "+result.id());publish(c,get(c,patient,previous.id()));
                    journal.audit(c,previous.id(),"SERVICEREQUEST_SUPERSEDED","Replacement "+result.id());
                }
            }journal.audit(c,id,"SYNTHETIC_AUTHORIZED",request.lateActionReason());journal.event(c,id,"AUTHORIZED/"+result.version(),result.ownerId());return result;
        });
    }
    public ServiceRequestRecord abandon(Context c,UUID patient,UUID id,UUID key,ServiceRequestReason request) { return end(c,patient,id,key,request,true); }
    public ServiceRequestRecord cancel(Context c,UUID patient,UUID id,UUID key,ServiceRequestReason request) { return end(c,patient,id,key,request,false); }
    private ServiceRequestRecord end(Context c,UUID patient,UUID id,UUID key,ServiceRequestReason request,boolean abandon) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),abandon?InterventionAuthorization.Action.PREPARE:InterventionAuthorization.Action.CONTINUE_EXISTING);required(request.reason(),"REASON_REQUIRED");
        return journal.execute(c,(abandon?"abandon-":"cancel-")+"service_request/"+id,key,request,ServiceRequestRecord.class,()->{
            var encounter=lockSource(c,patient,before);if(abandon)unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(abandon) draft(current);else if(!current.state().equals("AUTHORIZED")) fail("AUTHORIZED_RECORD_REQUIRED");
            if(!abandon && current.fulfillmentState().equals("FULFILLED")) fail("PERFORMED_CARE_CANNOT_BE_CANCELLED");
            c.jdbc().sql("UPDATE service_request SET state=:state,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("state",abandon?"ABANDONED":"CANCELLED").param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.reason());var result=get(c,patient,id);publish(c,result);
            if(!abandon)work.register(c,new ph.epr.api.carecoordination.OwnedClinicalWork.Obligation(result.id(),kind(result.data()).name(),patient,result.encounterId(),result.siteId(),result.ownerId(),result.version(),"Confirm cancellation notice and disposition: "+request.reason(),result.data().requestedDate()));
            journal.audit(c,id,abandon?"DRAFT_ABANDONED":"SERVICEREQUEST_CANCELLED",request.reason());return result;
        });
    }
    public byte[] artifact(Context c,UUID patient,UUID id,UUID preparationId) {
        var current=get(c,patient,id);UUID artifact=current.artifactId();String hash=current.artifactHash();
        if(preparationId!=null) {
            links.require(c,kind(current.data()),InterventionAuthorization.Action.AUTHORIZE);
            var prep=c.jdbc().sql("SELECT source_version,artifact_id,artifact_hash FROM service_request_preparation WHERE practice_id=:p AND id=:id AND preparation_id=:candidate AND prepared_by=:a")
                .param("p",c.practiceId()).param("id",id).param("candidate",preparationId).param("a",c.actorId())
                .query((r,n)->new ServiceRequestPreparation(preparationId,r.getLong(1),r.getObject(2,UUID.class),r.getString(3)))
                .optional().orElseThrow(()->new ClinicalAccessException("ARTIFACT_UNAVAILABLE",404));
            if(prep.sourceVersion()!=current.version()) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409);
            artifact=prep.artifactId();hash=prep.artifactHash();
        }
        if(artifact==null) throw new ClinicalAccessException("AUTHORIZED_ARTIFACT_UNAVAILABLE",404);
        journal.audit(c,id,"SERVICEREQUEST_ARTIFACT_READ",null);return artifacts.readVerified(c,artifact,hash);
    }
    public List<ServiceRequestRecord> history(Context c,UUID patient,UUID id) {
        var current=get(c,patient,id);
        return c.jdbc().sql("SELECT version,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,received_at FROM service_request_revision WHERE practice_id=:p AND id=:id ORDER BY version")
            .param("p",c.practiceId()).param("id",id).query((r,n)->new ServiceRequestRecord(id,patient,current.encounterId(),current.siteId(),r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class),current.authorId(),current.occurredAt(),r.getObject(9,OffsetDateTime.class),Set.of("DRAFT","ABANDONED").contains(r.getString(2))?null:current.authorizedAt(),r.getObject(7,UUID.class),r.getString(8),current.supersedesId(),data(c,id,r.getLong(1)))).list();
    }
    private void revision(Context c,UUID id,long version,ServiceRequestData data,String reason) {
        c.jdbc().sql("INSERT INTO service_request_revision(practice_id,id,version,kind,group_id,catalog_code,catalog_version,service_name,indication,priority,destination,specimen,body_site,laterality,instructions,requested_date,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,reason,recorded_by) SELECT practice_id,id,:v,:kind,:groupId,:catalogCode,:catalogVersion,:serviceName,:indication,:priority,:destination,:specimen,:bodySite,:laterality,:instructions,:requestedDate,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,:reason,:a FROM service_request WHERE practice_id=:p AND id=:id")
            .param("p",c.practiceId()).param("id",id).param("v",version).param("kind",data.kind(),java.sql.Types.VARCHAR).param("groupId",data.groupId(),java.sql.Types.OTHER).param("catalogCode",data.catalogCode(),java.sql.Types.VARCHAR).param("catalogVersion",data.catalogVersion(),java.sql.Types.VARCHAR).param("serviceName",data.serviceName(),java.sql.Types.VARCHAR).param("indication",data.indication(),java.sql.Types.VARCHAR).param("priority",data.priority(),java.sql.Types.VARCHAR).param("destination",data.destination(),java.sql.Types.VARCHAR).param("specimen",data.specimen(),java.sql.Types.VARCHAR).param("bodySite",data.bodySite(),java.sql.Types.VARCHAR).param("laterality",data.laterality(),java.sql.Types.VARCHAR).param("instructions",data.instructions(),java.sql.Types.VARCHAR).param("requestedDate",data.requestedDate(),java.sql.Types.DATE).param("reason",reason,java.sql.Types.VARCHAR).param("a",c.actorId()).update();
        saveItems(c,id,version,data);
    }
    private void publish(Context c,ServiceRequestRecord record) {
        var data=record.data();
        links.append(c,record.patientId(),record.encounterId(),new Summary(kind(data),record.id(),record.version(),data.serviceName(),List.of(text(data.indication()),text(data.destination()),text(data.specimen()),text(data.bodySite()),text(data.laterality()),text(data.instructions())),
            record.state(),record.deliveryState(),record.fulfillmentState(),record.reviewState(),record.ownerId(),data.requestedDate(),record.authorizedAt()==null?record.occurredAt():record.authorizedAt(),record.artifactId(),record.artifactHash(),data.groupId()));
    }
    private byte[] render(Context c,ServiceRequestRecord record) {
        var encounter=record.authorId().equals(c.actorId())?links.context(c,record.patientId(),record.encounterId()):links.acceptedWorkContext(c,record.patientId(),record.encounterId());var data=record.data();
        var lines=new ArrayList<String>();lines.add("Synthetic ServiceRequest · reviewed source version "+record.version());
        lines.add("Engineering validation only. Not valid for real-patient issuance.");lines.add(encounter.patientName()+" · "+encounter.recordNumber());
        lines.add("Practice "+c.practiceId()+" · site "+c.siteId());lines.add("Responsible clinician "+record.ownerId());
        lines.add(data.serviceName());lines.addAll(List.of(text(data.indication()),text(data.destination()),text(data.specimen()),text(data.bodySite()),text(data.laterality()),text(data.instructions())));lines.add("Priority: "+text(data.priority()));lines.add("Requested date: "+(data.requestedDate()==null?"Not recorded":data.requestedDate()));lines.add("Clinical time "+record.occurredAt());
        lines.add("Authorization is separate from dispensing, performance, receipt and review.");
        return ("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Synthetic ServiceRequest</title><style>body{font:16px/1.6 sans-serif;color:#192943;max-width:760px;margin:32px auto;padding:24px}p{white-space:pre-wrap;overflow-wrap:anywhere}</style><body>"+
            lines.stream().map(s->"<p>"+escape(s)+"</p>").collect(java.util.stream.Collectors.joining())+"</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private Kind kind(ServiceRequestData data) { return Kind.valueOf(data.kind()); }
    private void bump(Context c,UUID id) { c.jdbc().sql("UPDATE service_request SET version=version+1 WHERE practice_id=:p AND id=:id").param("p",c.practiceId()).param("id",id).update(); }
    private void newDecision(EncounterContext encounter) { if(!encounter.careState().equals("IN_PROGRESS")||encounter.noteState().equals("SIGNED")) throw new ClinicalAccessException("NEW_FOLLOW_UP_ENCOUNTER_REQUIRED",409); }
    private void unsigned(EncounterContext encounter) { if("SIGNED".equals(encounter.noteState())) throw new ClinicalAccessException("SIGNED_NOTE_IMMUTABLE",409); }
    private void version(ServiceRequestRecord r,long expected) { if(r.version()!=expected) throw new ClinicalAccessException("VERSION_CONFLICT",409); }
    private void draft(ServiceRequestRecord r) { if(!r.state().equals("DRAFT")) throw new ClinicalAccessException("AUTHORIZED_RECORD_IMMUTABLE",409); }
    private static String text(String s) { return s==null?"":s; }
    private static String escape(String s) { return text(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private static void required(String s,String code) { if(s==null||s.isBlank()||s.length()>4000) fail(code); }
    private static void fail(String code) { throw new ClinicalAccessException(code,422); }
    private void saveItems(Context c,UUID id,long version,ServiceRequestData data) {}
    private void obligation(Context c,ServiceRequestRecord record) {work.register(c,new ph.epr.api.carecoordination.OwnedClinicalWork.Obligation(record.id(),kind(record.data()).name(),record.patientId(),record.encounterId(),record.siteId(),record.ownerId(),record.version(),"Review outcome for "+record.data().serviceName(),record.data().requestedDate()));}
    private void prerequisites(Context c,ServiceRequestRecord record) {
        var data=record.data();
        if(data.catalogCode()==null||data.catalogCode().isBlank())return;
        if(!c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM service_request_catalog WHERE code=:code AND version=:version AND kind=:kind AND label=:label)")
            .param("code",data.catalogCode()).param("version",data.catalogVersion()).param("kind",data.kind()).param("label",data.serviceName()).query(Boolean.class).single())fail("CATALOG_SERVICE_MISMATCH");
    }
public record ServiceCatalogEntry(String code,String version,String kind,String label) {}
    public List<ServiceCatalogEntry> catalog(Context c){
        var kinds=java.util.stream.Stream.of(Kind.LAB_ORDER,Kind.IMAGING_ORDER).filter(k->{try{links.require(c,k,InterventionAuthorization.Action.READ);return true;}catch(ClinicalAccessException e){return false;}}).map(Enum::name).toList();
        return c.jdbc().sql("SELECT code,version,kind,label FROM service_request_catalog WHERE NOT retired ORDER BY label").query((r,n)->new ServiceCatalogEntry(r.getString(1),r.getString(2),r.getString(3),r.getString(4))).list().stream().filter(x->kinds.contains(x.kind())).toList();
    }
    private void validate(ServiceRequestData data,boolean authorize){
        if(data==null||data.kind()==null||!Set.of("LAB_ORDER","IMAGING_ORDER").contains(data.kind()))fail("SUPPORTED_REQUEST_KIND_REQUIRED");
        required(data.serviceName(),"SERVICE_REQUIRED");
        boolean hasCode=data.catalogCode()!=null&&!data.catalogCode().isBlank(), hasVersion=data.catalogVersion()!=null&&!data.catalogVersion().isBlank();
        if(hasCode!=hasVersion)fail("CATALOG_REFERENCE_INCOMPLETE");
        if(authorize){required(data.indication(),"CLINICAL_QUESTION_REQUIRED");required(data.destination(),"DESTINATION_REQUIRED");required(data.priority(),"PRIORITY_REQUIRED");if(data.requestedDate()==null)fail("REQUESTED_DATE_REQUIRED");}
    }
    private void duplicates(Context c,ServiceRequestRecord record,String reason){
        var data=record.data();
        boolean coded=data.catalogCode()!=null&&!data.catalogCode().isBlank();
        var match=coded?"r.catalog_code=:code":"(r.catalog_code IS NULL OR btrim(r.catalog_code)='') AND lower(btrim(r.service_name))=lower(btrim(:name))";
        var query=c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM service_request s JOIN service_request_revision r ON r.practice_id=s.practice_id AND r.id=s.id AND r.version=s.version WHERE s.practice_id=:p AND s.patient_id=:patient AND s.id<>:id AND s.state='AUTHORIZED' AND s.fulfillment_state<>'FULFILLED' AND "+match+")").param("p",c.practiceId()).param("patient",record.patientId()).param("id",record.id());
        if(coded)query.param("code",data.catalogCode());else query.param("name",data.serviceName());
        if(query.query(Boolean.class).single())required(reason,"DUPLICATE_INTENT_REASON_REQUIRED");
    }
    public record ServiceRequestBatch(UUID groupId,List<ServiceRequestCreate> requests) {}
    public record ServiceRequestBatchResult(UUID groupId,List<ServiceRequestRecord> requests) {}
    public ServiceRequestBatchResult createBatch(Context c,UUID patient,UUID key,ServiceRequestBatch request){
        if(request.groupId()==null||request.requests()==null||request.requests().isEmpty()||request.requests().size()>30)fail("BATCH_REQUESTS_REQUIRED");
        var encounter=request.requests().getFirst().encounterId();links.context(c,patient,encounter);
        for(var item:request.requests()){if(item.data()==null||!request.groupId().equals(item.data().groupId())||!encounter.equals(item.encounterId()))fail("BATCH_CONTEXT_MISMATCH");links.require(c,kind(item.data()),InterventionAuthorization.Action.PREPARE);}
        return journal.execute(c,"create-request-group/"+patient,key,request,ServiceRequestBatchResult.class,()->{
            links.lock(c,patient,encounter);
            c.jdbc().sql("INSERT INTO requisition_group(practice_id,patient_id,encounter_id,site_id,id,created_by) VALUES(:p,:patient,:e,:s,:id,:a)").param("p",c.practiceId()).param("patient",patient).param("e",encounter).param("s",c.siteId()).param("id",request.groupId()).param("a",c.actorId()).update();
            var result=new ServiceRequestBatchResult(request.groupId(),request.requests().stream().map(item->create(c,patient,item.id(),item)).toList());
            return result;
        });
    }


    public ServiceRequestRecord delivery(Context c,UUID patient,UUID id,UUID key,ServiceRequestEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("ACKNOWLEDGED","DELIVERED","FAILED","PRINTED","UNKNOWN").contains(request.outcome()))fail("INVALID_DELIVERY_EVIDENCE");
        return journal.execute(c,"service_request-delivery/"+id,key,request,ServiceRequestRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");
            c.jdbc().sql("INSERT INTO service_request_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'DELIVERY',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE service_request SET delivery_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);
            journal.audit(c,id,"SERVICEREQUEST_DELIVERY_RECORDED",null);return result;
        });
    }

    public ServiceRequestRecord fulfillment(Context c,UUID patient,UUID id,UUID key,ServiceRequestEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("FULFILLED","PARTIAL","UNABLE").contains(request.outcome()))fail("INVALID_FULFILLMENT_EVIDENCE");
        return journal.execute(c,"service_request-fulfillment/"+id,key,request,ServiceRequestRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");
            c.jdbc().sql("INSERT INTO service_request_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'FULFILLMENT',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE service_request SET fulfillment_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);
            journal.audit(c,id,"SERVICEREQUEST_FULFILLMENT_RECORDED",null);return result;
        });
    }

    public ServiceRequestRecord result(Context c,UUID patient,UUID id,UUID key,ServiceRequestEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("CORRECTED","RECEIVED").contains(request.outcome()))fail("INVALID_RESULT_EVIDENCE");
        return journal.execute(c,"service_request-result/"+id,key,request,ServiceRequestRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");
            c.jdbc().sql("INSERT INTO service_request_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'RESULT',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE service_request SET review_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);obligation(c,result);
            
            journal.audit(c,id,"SERVICEREQUEST_RESULT_RECORDED",null);return result;
        });
    }

    public ServiceRequestRecord review(Context c,UUID patient,UUID id,UUID key,ServiceRequestEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("REVIEWED").contains(request.outcome()))fail("INVALID_REVIEW_EVIDENCE");
        return journal.execute(c,"service_request-review/"+id,key,request,ServiceRequestRecord.class,()->{
            lockSource(c,patient,before);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");if(current.state().equals("DRAFT")||current.state().equals("ABANDONED"))fail("RELEASED_SOURCE_REQUIRED");if(!Set.of("RECEIVED","CORRECTED").contains(current.reviewState()))fail("MATCHED_REPORT_REQUIRED");
            c.jdbc().sql("INSERT INTO service_request_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'REVIEW',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE service_request SET review_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);work.reviewed(c,kind(result.data()).name(),id,result.version(),request.evidence());
            journal.audit(c,id,"SERVICEREQUEST_REVIEW_RECORDED",null);return result;
        });
    }
    private EncounterContext lockSource(Context c,UUID patient,ServiceRequestRecord source) {
        if(source.authorId().equals(c.actorId()))return links.lock(c,patient,source.encounterId());
        work.requireAcceptedAssignment(c,kind(source.data()).name(),source.id(),patient,source.encounterId());
        return links.lockAcceptedWork(c,patient,source.encounterId());
    }

    void lockForReport(Context c,UUID patient,UUID id){var source=get(c,patient,id);lockSource(c,patient,source);}
    public record GroupPrint(UUID documentId) {}
    public record GroupMember(UUID sourceId,long version,String state,UUID artifactId,String artifactHash) {}
    public record GroupCapture(UUID documentId,UUID groupId,List<GroupMember> members,byte[] bytes) {}
    public record GroupDocument(UUID documentId,UUID groupId,List<GroupMember> members,UUID artifactId,String artifactHash) {}
    public List<ServiceRequestRecord> group(Context c,UUID patient,UUID group){
        var ids=c.jdbc().sql("SELECT request_id FROM requisition_group_member WHERE practice_id=:p AND patient_id=:patient AND group_id=:g ORDER BY request_id").param("p",c.practiceId()).param("patient",patient).param("g",group).query(UUID.class).list();
        if(ids.isEmpty())throw new ClinicalAccessException("REQUISITION_GROUP_UNAVAILABLE",404);
        return ids.stream().map(id->get(c,patient,id)).toList();
    }
    public GroupCapture captureGroup(Context c,UUID patient,UUID group,UUID key,GroupPrint request){
        var initial=group(c,patient,group);if(request.documentId()==null)fail("DOCUMENT_ID_REQUIRED");
        return journal.execute(c,"capture-requisition-group/"+group,key,request,GroupCapture.class,()->{
            lockSource(c,patient,initial.getFirst());var records=group(c,patient,group);
            if(records.stream().anyMatch(x->x.authorizedAt()==null))fail("GROUP_HAS_UNAUTHORIZED_REQUESTS");
            var identity=links.acceptedWorkContext(c,patient,records.getFirst().encounterId());
            var html=new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Synthetic grouped requisition</title><style>body{font:16px/1.6 sans-serif;margin:32px;color:#192943}p{white-space:pre-wrap;overflow-wrap:anywhere}section{break-inside:avoid;border-top:1px solid #ddd}</style><body><h1>Synthetic grouped requisition</h1><p>Engineering validation only. Not valid for real-patient issuance.</p><p>").append(escape(identity.patientName())).append(" · ").append(escape(identity.recordNumber())).append("</p>");
            for(var source:records)html.append("<section><h2>").append(escape(source.data().serviceName())).append("</h2><p>").append(escape(source.data().indication())).append("</p><p>").append(escape(source.data().destination())).append("</p><p>").append(escape(source.data().instructions())).append("</p><p>Priority: ").append(escape(source.data().priority())).append(" · requested date: ").append(source.data().requestedDate()).append(" · specimen: ").append(escape(source.data().specimen())).append(" · body site: ").append(escape(source.data().bodySite())).append(" · laterality: ").append(escape(source.data().laterality())).append("</p><p>Request ").append(source.id()).append(" · ").append(source.state()).append(" · authorized ").append(source.authorizedAt()).append(" · authorizer ").append(source.authorId()).append("</p></section>");
            html.append("<p>Printing is separate from delivery, performance and review.</p></body></html>");
            return new GroupCapture(request.documentId(),group,records.stream().map(x->new GroupMember(x.id(),x.version(),x.state(),x.artifactId(),x.artifactHash())).toList(),html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
    }
    public GroupDocument finalizeGroup(Context c,UUID patient,UUID key,GroupCapture capture,PrivateClinicalArtifacts.Artifact staged){
        var initial=group(c,patient,capture.groupId());
        return journal.execute(c,"archive-requisition-group/"+capture.groupId(),key,capture,GroupDocument.class,()->{
            lockSource(c,patient,initial.getFirst());
            if(!group(c,patient,capture.groupId()).stream().map(ServiceRequestRecord::id).toList().equals(capture.members().stream().map(GroupMember::sourceId).toList()))throw new ClinicalAccessException("GROUP_DOCUMENT_STALE",409);
            for(var member:capture.members())if(get(c,patient,member.sourceId()).version()!=member.version())throw new ClinicalAccessException("GROUP_DOCUMENT_STALE",409);
            if(!staged.artifactId().equals(capture.documentId())||!staged.sha256().equals(PrivateClinicalArtifacts.sha256(capture.bytes())))fail("GROUP_ARTIFACT_MISMATCH");
            artifacts.attach(c,staged);artifacts.archive(c,staged.artifactId(),staged.sha256());
            c.jdbc().sql("INSERT INTO requisition_group_document(practice_id,patient_id,group_id,document_id,members,artifact_id,artifact_hash,created_by) VALUES(:p,:patient,:g,:id,CAST(:members AS jsonb),:artifact,:hash,:a)")
                .param("p",c.practiceId()).param("patient",patient).param("g",capture.groupId()).param("id",capture.documentId()).param("members",ClinicalJson.write(capture.members())).param("artifact",staged.artifactId()).param("hash",staged.sha256()).param("a",c.actorId()).update();
            journal.audit(c,capture.documentId(),"SYNTHETIC_GROUP_REQUISITION_ARCHIVED",null);
            return new GroupDocument(capture.documentId(),capture.groupId(),capture.members(),staged.artifactId(),staged.sha256());
        });
    }
    public byte[] groupArtifact(Context c,UUID patient,UUID group,UUID id){
        var doc=c.jdbc().sql("SELECT members::text,artifact_id,artifact_hash FROM requisition_group_document WHERE practice_id=:p AND patient_id=:patient AND group_id=:g AND document_id=:id").param("p",c.practiceId()).param("patient",patient).param("g",group).param("id",id)
            .query((r,n)->new GroupDocument(id,group,Arrays.asList(ClinicalJson.read(r.getString(1),GroupMember[].class)),r.getObject(2,UUID.class),r.getString(3))).optional().orElseThrow(()->new ClinicalAccessException("GROUP_DOCUMENT_UNAVAILABLE",404));
        for(var member:doc.members())get(c,patient,member.sourceId());
        return artifacts.readVerified(c,doc.artifactId(),doc.artifactHash());
    }
}
