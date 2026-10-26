package ph.epr.api.prescribing.application;

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

/** Prescription records and revisions belong exclusively to prescribing. */
@Service
@Profile("local")
public class PrescriptionService {
    private final EncounterInterventionLinks links;
    private final TenantCommandJournal journal;
    private final PrivateClinicalArtifacts artifacts;
    private final ph.epr.api.clinicalchart.ClinicalLists clinicalLists;
    public PrescriptionService(EncounterInterventionLinks links,TenantCommandJournal journal,PrivateClinicalArtifacts artifacts, ph.epr.api.clinicalchart.ClinicalLists clinicalLists) {
        this.links=links;this.journal=journal;this.artifacts=artifacts;this.clinicalLists=clinicalLists;
    }
    public record PrescriptionData(String indication, String instructions, UUID reviewId, List<PrescriptionItem> items) {}
    public record PrescriptionRecord(UUID id,UUID patientId,UUID encounterId,UUID siteId,long version,String state,
        String deliveryState,String fulfillmentState,String reviewState,UUID ownerId,UUID authorId,
        OffsetDateTime occurredAt,OffsetDateTime receivedAt,OffsetDateTime authorizedAt,UUID artifactId,String artifactHash,UUID supersedesId,PrescriptionData data) {}
    public record PrescriptionCreate(UUID id,UUID encounterId,OffsetDateTime occurredAt,PrescriptionData data,UUID supersedesId) {}
    public record PrescriptionUpdate(long expectedVersion,PrescriptionData data) {}
    public record PrescriptionPrepare(UUID preparationId,long expectedVersion) {}
    public record PrescriptionPreparation(UUID preparationId,long sourceVersion,UUID artifactId,String artifactHash,UUID clinicalReviewId,String clinicalReviewHash) {}
    public record PrescriptionAuthorize(long expectedVersion,UUID preparationId,String reviewedArtifactHash,boolean confirmSyntheticReview,String lateActionReason,String duplicateReason) {}
    public record PrescriptionReason(long expectedVersion,String reason) {}
    public record PrescriptionEvidence(long expectedVersion,String outcome,String evidence,OffsetDateTime occurredAt) {}
    public List<PrescriptionRecord> list(Context c,UUID patient,int page) {
        if(page<0)throw new ClinicalAccessException("INVALID_PAGE",422);
        var ids=c.jdbc().sql("SELECT id FROM prescription WHERE practice_id=:p AND patient_id=:patient AND site_id=:s AND author_id=:a ORDER BY received_at DESC,id LIMIT 30 OFFSET :offset")
            .param("p",c.practiceId()).param("patient",patient).param("s",c.siteId()).param("a",c.actorId()).param("offset",(long)page*30).query(UUID.class).list();
        return ids.stream().map(id->get(c,patient,id)).toList();
    }
    public PrescriptionRecord get(Context c,UUID patient,UUID id) {
        var record=load(c,patient,id);
        links.context(c,patient,record.encounterId());links.require(c,kind(record.data()),InterventionAuthorization.Action.READ);
        return record;
    }
    private PrescriptionRecord load(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT * FROM prescription WHERE practice_id=:p AND patient_id=:patient AND site_id=:site AND id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("site",c.siteId()).param("id",id)
            .query((r,n)->new PrescriptionRecord(id,patient,r.getObject("encounter_id",UUID.class),r.getObject("site_id",UUID.class),r.getLong("version"),r.getString("state"),
                r.getString("delivery_state"),r.getString("fulfillment_state"),r.getString("review_state"),r.getObject("owner_id",UUID.class),r.getObject("author_id",UUID.class),
                r.getObject("occurred_at",OffsetDateTime.class),r.getObject("received_at",OffsetDateTime.class),r.getObject("authorized_at",OffsetDateTime.class),
                r.getObject("artifact_id",UUID.class),r.getString("artifact_hash"),r.getObject("supersedes_id",UUID.class),data(c,id,r.getLong("version"))))
            .optional().orElseThrow(()->new ClinicalAccessException("PRESCRIPTION_UNAVAILABLE",404));
    }
    private PrescriptionData data(Context c,UUID id,long version) {
        return c.jdbc().sql("SELECT * FROM prescription_revision WHERE practice_id=:p AND id=:id AND version=:v")
            .param("p",c.practiceId()).param("id",id).param("v",version).query((r,n)->new PrescriptionData(r.getString("indication"), r.getString("instructions"), r.getObject("review_id",UUID.class), items(c,id,version))).single();
    }
    public PrescriptionRecord create(Context c,UUID patient,UUID key,PrescriptionCreate request) {
        if(request.id()==null||request.encounterId()==null||request.occurredAt()==null||request.data()==null) fail("CONTEXT_REQUIRED");
        validate(request.data(),false);
        links.context(c,patient,request.encounterId());links.require(c,kind(request.data()),InterventionAuthorization.Action.PREPARE);
        return journal.execute(c,"create-prescription/"+patient,key,request,PrescriptionRecord.class,()->{
            var encounter=links.lock(c,patient,request.encounterId());newDecision(encounter);
            validateRefills(c,patient,request.data());
            if(request.supersedesId()!=null) {
                var original=get(c,patient,request.supersedesId());
                if(!original.state().equals("ISSUED") && !original.state().equals("CANCELLED")) fail("AUTHORIZED_ORIGINAL_REQUIRED");
            }
            if(c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM prescription WHERE practice_id=:p AND id=:id)").param("p",c.practiceId()).param("id",request.id()).query(Boolean.class).single())throw new ClinicalAccessException("SOURCE_ID_ALREADY_USED",409);
            c.jdbc().sql("INSERT INTO prescription(practice_id,patient_id,encounter_id,site_id,id,owner_id,author_id,occurred_at,supersedes_id) VALUES(:p,:patient,:e,:site,:id,:a,:a,:time,:original)")
                .param("p",c.practiceId()).param("patient",patient).param("e",request.encounterId()).param("site",c.siteId()).param("id",request.id()).param("a",c.actorId())
                .param("time",request.occurredAt()).param("original",request.supersedesId(),java.sql.Types.OTHER).update();
            revision(c,request.id(),0,request.data(),null);
            var result=get(c,patient,request.id());publish(c,result);journal.audit(c,result.id(),"PRESCRIPTION_DRAFT_CREATED",null);return result;
        });
    }
    public PrescriptionRecord update(Context c,UUID patient,UUID id,UUID key,PrescriptionUpdate request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.PREPARE);validate(request.data(),false);
        return journal.execute(c,"update-prescription/"+id,key,request,PrescriptionRecord.class,()->{
            var encounter=links.lock(c,patient,before.encounterId());newDecision(encounter);
            var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);
            validateRefills(c,patient,request.data());
            if(kind(current.data())!=kind(request.data())) fail("KIND_IMMUTABLE");
            bump(c,id);revision(c,id,current.version()+1,request.data(),null);
            var result=get(c,patient,id);publish(c,result);journal.audit(c,id,"PRESCRIPTION_DRAFT_UPDATED",null);return result;
        });
    }
    public byte[] candidateContent(Context c,UUID patient,UUID id,PrescriptionPrepare request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        var encounter=links.lock(c,patient,before.encounterId());unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);validate(current.data(),true);validateRefills(c,patient,current.data());var review=prerequisites(c,current);
        return render(c,current,review);
    }
    public PrescriptionPreparation prepare(Context c,UUID patient,UUID id,UUID key,PrescriptionPrepare request) {
        return prepare(c,patient,id,key,request,null);
    }
    public PrescriptionPreparation prepare(Context c,UUID patient,UUID id,UUID key,PrescriptionPrepare request,PrivateClinicalArtifacts.Artifact staged) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        return journal.execute(c,"prepare-prescription/"+id,key,request,PrescriptionPreparation.class,()->{
            var encounter=links.lock(c,patient,before.encounterId());unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);validate(current.data(),true);validateRefills(c,patient,current.data());var review=prerequisites(c,current);
            if(request.preparationId()==null) fail("PREPARATION_ID_REQUIRED");
            var artifact=staged==null?artifacts.stage(c,request.preparationId(),render(c,current,review)):staged;
            if(!artifact.artifactId().equals(request.preparationId()))fail("ARTIFACT_CONTEXT_MISMATCH");
            if(staged!=null) { if(!PrivateClinicalArtifacts.sha256(render(c,current,review)).equals(staged.sha256())) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409); artifacts.attach(c,staged); }
            c.jdbc().sql("INSERT INTO prescription_preparation(practice_id,id,preparation_id,source_version,artifact_id,artifact_hash,prepared_by,clinical_review_id,clinical_review_hash) VALUES(:p,:id,:candidate,:v,:artifact,:hash,:a,:review,:reviewHash)")
                .param("p",c.practiceId()).param("id",id).param("candidate",request.preparationId()).param("v",current.version()).param("artifact",artifact.artifactId()).param("hash",artifact.sha256()).param("a",c.actorId()).param("review",review.reviewId()).param("reviewHash",review.manifestHash()).update();
            return new PrescriptionPreparation(request.preparationId(),current.version(),artifact.artifactId(),artifact.sha256(),review.reviewId(),review.manifestHash());
        });
    }
    public PrescriptionRecord authorize(Context c,UUID patient,UUID id,UUID key,PrescriptionAuthorize request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.AUTHORIZE);
        return journal.execute(c,"authorize-prescription/"+id,key,request,PrescriptionRecord.class,()->{
            var original=before.supersedesId()==null?null:get(c,patient,before.supersedesId());
            var boundaries=new java.util.TreeSet<UUID>();boundaries.add(before.encounterId());if(original!=null)boundaries.add(original.encounterId());
            for(var item:before.data().items())if(item.sourcePrescriptionId()!=null)boundaries.add(get(c,patient,item.sourcePrescriptionId()).encounterId());
            for(var boundary:boundaries)links.lock(c,patient,boundary);
            var encounter=links.context(c,patient,before.encounterId());unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());draft(current);
            if(!request.confirmSyntheticReview()) fail("DELIBERATE_SYNTHETIC_REVIEW_REQUIRED");
            if(!encounter.careState().equals("IN_PROGRESS")) required(request.lateActionReason(),"LATE_ACTION_REASON_REQUIRED");
            validate(current.data(),true);validateRefills(c,patient,current.data());var review=prerequisites(c,current);duplicates(c,current,request.duplicateReason());
            var prep=c.jdbc().sql("SELECT preparation_id,source_version,artifact_id,artifact_hash,clinical_review_id,clinical_review_hash FROM prescription_preparation WHERE practice_id=:p AND id=:id AND preparation_id=:candidate AND prepared_by=:a")
                .param("p",c.practiceId()).param("id",id).param("candidate",request.preparationId()).param("a",c.actorId())
                .query((r,n)->new PrescriptionPreparation(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class),r.getString(4),r.getObject(5,UUID.class),r.getString(6)))
                .optional().orElseThrow(()->new ClinicalAccessException("REVIEW_CANDIDATE_REQUIRED",422));
            if(prep.sourceVersion()!=current.version()||!prep.artifactHash().equals(request.reviewedArtifactHash())||!review.reviewId().equals(prep.clinicalReviewId())||!review.manifestHash().equals(prep.clinicalReviewHash())) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409);
            artifacts.archive(c,prep.artifactId(),prep.artifactHash());
            c.jdbc().sql("UPDATE prescription SET state='ISSUED',version=version+1,authorized_at=CURRENT_TIMESTAMP,artifact_id=:artifact,artifact_hash=:hash,delivery_state='NOT_SENT' WHERE practice_id=:p AND id=:id")
                .param("artifact",prep.artifactId()).param("hash",prep.artifactHash()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.lateActionReason());
            var result=get(c,patient,id);publish(c,result);obligation(c,result);
            if(original!=null) {
                var previous=get(c,patient,original.id());
                if(!Set.of("ISSUED","CANCELLED").contains(previous.state()))throw new ClinicalAccessException("ORIGINAL_SUPERSESSION_CONFLICT",409);
                if(!kind(previous.data()).equals(kind(result.data())))fail("SUPERSESSION_KIND_MISMATCH");
                if(previous.state().equals("ISSUED")) {
                    c.jdbc().sql("UPDATE prescription SET state='SUPERSEDED',version=version+1 WHERE practice_id=:p AND id=:original").param("p",c.practiceId()).param("original",previous.id()).update();
                    revision(c,previous.id(),previous.version()+1,previous.data(),"Superseded by "+result.id());publish(c,get(c,patient,previous.id()));
                    journal.audit(c,previous.id(),"PRESCRIPTION_SUPERSEDED","Replacement "+result.id());
                }
            }journal.audit(c,id,"SYNTHETIC_ISSUED",request.lateActionReason());journal.event(c,id,"ISSUED/"+result.version(),result.ownerId());return result;
        });
    }
    public PrescriptionRecord abandon(Context c,UUID patient,UUID id,UUID key,PrescriptionReason request) { return end(c,patient,id,key,request,true); }
    public PrescriptionRecord cancel(Context c,UUID patient,UUID id,UUID key,PrescriptionReason request) { return end(c,patient,id,key,request,false); }
    private PrescriptionRecord end(Context c,UUID patient,UUID id,UUID key,PrescriptionReason request,boolean abandon) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),abandon?InterventionAuthorization.Action.PREPARE:InterventionAuthorization.Action.CONTINUE_EXISTING);required(request.reason(),"REASON_REQUIRED");
        return journal.execute(c,(abandon?"abandon-":"cancel-")+"prescription/"+id,key,request,PrescriptionRecord.class,()->{
            var encounter=links.lock(c,patient,before.encounterId());if(abandon)unsigned(encounter);var current=get(c,patient,id);version(current,request.expectedVersion());
            if(abandon) draft(current);else if(!current.state().equals("ISSUED")) fail("AUTHORIZED_RECORD_REQUIRED");
            if(!abandon && current.fulfillmentState().equals("FULFILLED")) fail("PERFORMED_CARE_CANNOT_BE_CANCELLED");
            c.jdbc().sql("UPDATE prescription SET state=:state,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("state",abandon?"ABANDONED":"CANCELLED").param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.reason());var result=get(c,patient,id);publish(c,result);
            journal.audit(c,id,abandon?"DRAFT_ABANDONED":"PRESCRIPTION_CANCELLED",request.reason());return result;
        });
    }
    public byte[] artifact(Context c,UUID patient,UUID id,UUID preparationId) {
        var current=get(c,patient,id);UUID artifact=current.artifactId();String hash=current.artifactHash();
        if(preparationId!=null) {
            links.require(c,kind(current.data()),InterventionAuthorization.Action.AUTHORIZE);
            var prep=c.jdbc().sql("SELECT source_version,artifact_id,artifact_hash,clinical_review_id,clinical_review_hash FROM prescription_preparation WHERE practice_id=:p AND id=:id AND preparation_id=:candidate AND prepared_by=:a")
                .param("p",c.practiceId()).param("id",id).param("candidate",preparationId).param("a",c.actorId())
                .query((r,n)->new PrescriptionPreparation(preparationId,r.getLong(1),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,UUID.class),r.getString(5)))
                .optional().orElseThrow(()->new ClinicalAccessException("ARTIFACT_UNAVAILABLE",404));
            if(prep.sourceVersion()!=current.version()) throw new ClinicalAccessException("INTERVENTION_CANDIDATE_STALE",409);
            artifact=prep.artifactId();hash=prep.artifactHash();
        }
        if(artifact==null) throw new ClinicalAccessException("AUTHORIZED_ARTIFACT_UNAVAILABLE",404);
        journal.audit(c,id,"PRESCRIPTION_ARTIFACT_READ",null);return artifacts.readVerified(c,artifact,hash);
    }
    public List<PrescriptionRecord> history(Context c,UUID patient,UUID id) {
        var current=get(c,patient,id);
        return c.jdbc().sql("SELECT version,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,received_at FROM prescription_revision WHERE practice_id=:p AND id=:id ORDER BY version")
            .param("p",c.practiceId()).param("id",id).query((r,n)->new PrescriptionRecord(id,patient,current.encounterId(),current.siteId(),r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class),current.authorId(),current.occurredAt(),r.getObject(9,OffsetDateTime.class),Set.of("DRAFT","ABANDONED").contains(r.getString(2))?null:current.authorizedAt(),r.getObject(7,UUID.class),r.getString(8),current.supersedesId(),data(c,id,r.getLong(1)))).list();
    }
    private void revision(Context c,UUID id,long version,PrescriptionData data,String reason) {
        c.jdbc().sql("INSERT INTO prescription_revision(practice_id,id,version,indication,instructions,review_id,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,reason,recorded_by) SELECT practice_id,id,:v,:indication,:instructions,:reviewId,state,delivery_state,fulfillment_state,review_state,owner_id,artifact_id,artifact_hash,:reason,:a FROM prescription WHERE practice_id=:p AND id=:id")
            .param("p",c.practiceId()).param("id",id).param("v",version).param("indication",data.indication(),java.sql.Types.VARCHAR).param("instructions",data.instructions(),java.sql.Types.VARCHAR).param("reviewId",data.reviewId(),java.sql.Types.OTHER).param("reason",reason,java.sql.Types.VARCHAR).param("a",c.actorId()).update();
        saveItems(c,id,version,data);
    }
    private void publish(Context c,PrescriptionRecord record) {
        var data=record.data();
        links.append(c,record.patientId(),record.encounterId(),new Summary(kind(data),record.id(),record.version(),"Prescription · " + data.items().size() + " medicine(s)",data.items().stream().map(i->i.ingredient()+" · brand "+text(i.brand())+" · formulation "+text(i.formulation())+" · product strength "+i.strength()+" · dose "+i.dose()+" "+i.doseUnit()+" · "+i.route()+" · "+i.frequency()+" · "+i.duration()+" · quantity "+i.quantity()+" "+i.quantityUnit()+" · "+i.instructions()).toList(),
            record.state(),record.deliveryState(),record.fulfillmentState(),record.reviewState(),record.ownerId(),null,record.authorizedAt()==null?record.occurredAt():record.authorizedAt(),record.artifactId(),record.artifactHash()));
    }
    private byte[] render(Context c,PrescriptionRecord record,ph.epr.api.clinicalchart.ClinicalLists.SafetyManifest review) {
        var encounter=links.context(c,record.patientId(),record.encounterId());var data=record.data();
        var lines=new ArrayList<String>();lines.add("Synthetic Prescription · reviewed source version "+record.version());
        lines.add("Engineering validation only. Not valid for real-patient issuance.");lines.add(encounter.patientName()+" · "+encounter.recordNumber());
        lines.add("Practice "+c.practiceId()+" · site "+c.siteId());lines.add("Responsible clinician "+record.ownerId());
        lines.add("Clinical-list reconciliation "+review.reviewId()+" · "+review.manifestHash());
        lines.add("Prescription · " + data.items().size() + " medicine(s)");lines.addAll(data.items().stream().map(i->i.ingredient()+" · brand "+text(i.brand())+" · formulation "+text(i.formulation())+" · product strength "+i.strength()+" · dose "+i.dose()+" "+i.doseUnit()+" · "+i.route()+" · "+i.frequency()+" · "+i.duration()+" · quantity "+i.quantity()+" "+i.quantityUnit()+" · "+i.instructions()).toList());lines.add("Indication: "+text(data.indication()));lines.add("Prescription instructions: "+text(data.instructions()));lines.add("Clinical time "+record.occurredAt());
        lines.add("Authorization is separate from dispensing, performance, receipt and review.");
        return ("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Synthetic Prescription</title><style>body{font:16px/1.6 sans-serif;color:#192943;max-width:760px;margin:32px auto;padding:24px}p{white-space:pre-wrap;overflow-wrap:anywhere}</style><body>"+
            lines.stream().map(s->"<p>"+escape(s)+"</p>").collect(java.util.stream.Collectors.joining())+"</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private Kind kind(PrescriptionData data) { return Kind.PRESCRIPTION; }
    private void bump(Context c,UUID id) { c.jdbc().sql("UPDATE prescription SET version=version+1 WHERE practice_id=:p AND id=:id").param("p",c.practiceId()).param("id",id).update(); }
    private void newDecision(EncounterContext encounter) { if(!encounter.careState().equals("IN_PROGRESS")||encounter.noteState().equals("SIGNED")) throw new ClinicalAccessException("NEW_FOLLOW_UP_ENCOUNTER_REQUIRED",409); }
    private void unsigned(EncounterContext encounter) { if("SIGNED".equals(encounter.noteState())) throw new ClinicalAccessException("SIGNED_NOTE_IMMUTABLE",409); }
    private void version(PrescriptionRecord r,long expected) { if(r.version()!=expected) throw new ClinicalAccessException("VERSION_CONFLICT",409); }
    private void draft(PrescriptionRecord r) { if(!r.state().equals("DRAFT")) throw new ClinicalAccessException("AUTHORIZED_RECORD_IMMUTABLE",409); }
    private static String text(String s) { return s==null?"":s; }
    private static String escape(String s) { return text(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private static void required(String s,String code) { if(s==null||s.isBlank()||s.length()>4000) fail(code); }
    private static void fail(String code) { throw new ClinicalAccessException(code,422); }
    public record PrescriptionItem(UUID itemId,String ingredient,String brand,String strength,String formulation,java.math.BigDecimal dose,String doseUnit,String route,String frequency,String duration,java.math.BigDecimal quantity,String quantityUnit,int repeats,String instructions,String catalogCode,String catalogVersion,UUID sourcePrescriptionId,UUID sourceItemId) {}
    public record MedicationCatalogEntry(String code,String version,String ingredient,String strength,String formulation,String doseUnit,String quantityUnit) {}
    public List<MedicationCatalogEntry> catalog(Context c){ links.require(c,Kind.PRESCRIPTION,InterventionAuthorization.Action.READ);return c.jdbc().sql("SELECT code,version,ingredient,strength,formulation,dose_unit,quantity_unit FROM prescribing_catalog WHERE NOT retired ORDER BY ingredient").query((r,n)->new MedicationCatalogEntry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7))).list(); }
    private List<PrescriptionItem> items(Context c,UUID id,long version){
        return c.jdbc().sql("SELECT * FROM prescription_item_revision WHERE practice_id=:p AND prescription_id=:id AND version=:v ORDER BY item_id")
            .param("p",c.practiceId()).param("id",id).param("v",version).query((r,n)->new PrescriptionItem(r.getObject("item_id",UUID.class),r.getString("ingredient"),r.getString("brand"),r.getString("strength"),r.getString("formulation"),r.getBigDecimal("dose"),r.getString("dose_unit"),r.getString("route"),r.getString("frequency"),r.getString("duration"),r.getBigDecimal("quantity"),r.getString("quantity_unit"),r.getInt("repeats"),r.getString("instructions"),r.getString("catalog_code"),r.getString("catalog_version"),r.getObject("source_prescription_id",UUID.class),r.getObject("source_item_id",UUID.class))).list();
    }
    private void saveItems(Context c,UUID id,long version,PrescriptionData data){
        for(var i:data.items())c.jdbc().sql("INSERT INTO prescription_item_revision(practice_id,prescription_id,version,item_id,ingredient,brand,strength,formulation,dose,dose_unit,route,frequency,duration,quantity,quantity_unit,repeats,instructions,catalog_code,catalog_version,source_prescription_id,source_item_id) VALUES(:p,:id,:v,:item,:ingredient,:brand,:strength,:formulation,:dose,:unit,:route,:frequency,:duration,:quantity,:quantityUnit,:repeats,:instructions,:code,:catalogVersion,:sourcePrescription,:sourceItem)")
            .param("p",c.practiceId()).param("id",id).param("v",version).param("item",i.itemId()).param("ingredient",i.ingredient()).param("brand",i.brand(),java.sql.Types.VARCHAR).param("strength",i.strength(),java.sql.Types.VARCHAR).param("formulation",i.formulation(),java.sql.Types.VARCHAR)
            .param("dose",i.dose(),java.sql.Types.NUMERIC).param("unit",i.doseUnit(),java.sql.Types.VARCHAR).param("route",i.route(),java.sql.Types.VARCHAR).param("frequency",i.frequency(),java.sql.Types.VARCHAR).param("duration",i.duration(),java.sql.Types.VARCHAR).param("quantity",i.quantity(),java.sql.Types.NUMERIC)
            .param("quantityUnit",i.quantityUnit(),java.sql.Types.VARCHAR).param("repeats",i.repeats()).param("instructions",i.instructions(),java.sql.Types.VARCHAR).param("code",i.catalogCode(),java.sql.Types.VARCHAR).param("catalogVersion",i.catalogVersion(),java.sql.Types.VARCHAR).param("sourcePrescription",i.sourcePrescriptionId(),java.sql.Types.OTHER).param("sourceItem",i.sourceItemId(),java.sql.Types.OTHER).update();
    }
    private void validate(PrescriptionData data,boolean issue){
        if(data==null||data.items()==null||data.items().isEmpty()||data.items().size()>30)fail("PRESCRIPTION_ITEMS_REQUIRED");
        var ids=new HashSet<UUID>();var ingredients=new HashSet<String>();
        for(var item:data.items()){
            if(item==null||item.itemId()==null||!ids.add(item.itemId()))fail("UNIQUE_ITEM_ID_REQUIRED");
            required(item.ingredient(),"GENERIC_INGREDIENT_REQUIRED");
            if(item.ingredient().length()>200)fail("INGREDIENT_TOO_LONG");
            if((item.sourcePrescriptionId()==null)!=(item.sourceItemId()==null))fail("REFILL_SOURCE_PAIR_REQUIRED");
            if((item.catalogCode()==null||item.catalogCode().isBlank())!=(item.catalogVersion()==null||item.catalogVersion().isBlank()))fail("CATALOG_SOURCE_PAIR_REQUIRED");
            if(!ingredients.add(item.ingredient().trim().toLowerCase(Locale.ROOT)))fail("DUPLICATE_INGREDIENT_IN_PRESCRIPTION");
            if(issue){required(item.strength(),"PRODUCT_STRENGTH_REQUIRED");required(item.formulation(),"FORMULATION_REQUIRED");required(item.doseUnit(),"DOSE_UNIT_REQUIRED");required(item.route(),"ROUTE_REQUIRED");required(item.frequency(),"FREQUENCY_REQUIRED");required(item.duration(),"DURATION_REQUIRED");required(item.quantityUnit(),"QUANTITY_UNIT_REQUIRED");required(item.instructions(),"PATIENT_INSTRUCTIONS_REQUIRED");
                if(item.dose()==null||item.dose().signum()<=0||item.quantity()==null||item.quantity().signum()<=0||item.repeats()!=0)fail("POSITIVE_DOSE_QUANTITY_AND_NO_REPEATS_REQUIRED");}
        }
        if(issue){required(data.indication(),"INDICATION_REQUIRED");if(data.reviewId()==null)fail("MEDICATION_SAFETY_REVIEW_REQUIRED");}
    }
    private void validateRefills(Context c,UUID patient,PrescriptionData data){
        var sources=new HashSet<String>();
        for(var item:data.items()){
            if(item.sourcePrescriptionId()==null)continue;
            var source=get(c,patient,item.sourcePrescriptionId());
            if(!source.state().equals("ISSUED"))fail("ISSUED_REFILL_SOURCE_REQUIRED");
            var original=source.data().items().stream().filter(i->i.itemId().equals(item.sourceItemId())).findFirst()
                .orElseThrow(()->new ClinicalAccessException("REFILL_ITEM_UNAVAILABLE",422));
            if(!original.ingredient().equalsIgnoreCase(item.ingredient()) || !Objects.equals(original.catalogCode(),item.catalogCode()))fail("REFILL_MEDICINE_CHANGED");
            if(!sources.add(item.sourcePrescriptionId()+"/"+item.sourceItemId()))fail("DUPLICATE_REFILL_SOURCE");
        }
    }
    private ph.epr.api.clinicalchart.ClinicalLists.SafetyManifest prerequisites(Context c,PrescriptionRecord record){
        var review=clinicalLists.requireCurrentSafety(c,record.patientId(),record.data().reviewId());
        for(var i:record.data().items()){
            if(i.catalogCode()!=null && !i.catalogCode().isBlank() && !c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM prescribing_catalog WHERE code=:code AND version=:v AND ingredient=:ingredient AND NOT retired)").param("code",i.catalogCode()).param("v",i.catalogVersion()).param("ingredient",i.ingredient()).query(Boolean.class).single())fail("SYNTHETIC_MEDICATION_SOURCE_REQUIRED");
            if(review.allergies().stream().anyMatch(entry->entry.code()==null))fail("UNCHECKABLE_ALLERGY_SOURCE");
            if(review.medications().stream().anyMatch(entry->entry.code()==null))fail("UNCHECKABLE_MEDICATION_SOURCE");
            if(review.allergies().stream().anyMatch(entry->entry.code().code().equalsIgnoreCase(i.catalogCode())||entry.display().equalsIgnoreCase(i.ingredient())))fail("KNOWN_ALLERGY_MATCH");
            if(review.medications().stream().anyMatch(entry->entry.code().code().equalsIgnoreCase(i.catalogCode())||entry.display().equalsIgnoreCase(i.ingredient())))fail("CURRENT_MEDICATION_DUPLICATE_REVIEW_REQUIRED");
        }
        return review;
    }
    private void duplicates(Context c,PrescriptionRecord record,String reason){
        for(var i:record.data().items()){
            var duplicate=c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM prescription p JOIN prescription_item_revision i ON i.practice_id=p.practice_id AND i.prescription_id=p.id AND i.version=p.version WHERE p.practice_id=:p AND p.patient_id=:patient AND p.id<>:id AND p.state='ISSUED' AND lower(i.ingredient)=lower(:ingredient))")
                .param("p",c.practiceId()).param("patient",record.patientId()).param("id",record.id()).param("ingredient",i.ingredient()).query(Boolean.class).single();
            if(duplicate)required(reason,"DUPLICATE_INTENT_REASON_REQUIRED");
        }
    }
    private void obligation(Context c,PrescriptionRecord record) { /* Dispensing and medication use are not inferred. */ }


    public PrescriptionRecord delivery(Context c,UUID patient,UUID id,UUID key,PrescriptionEvidence request) {
        var before=get(c,patient,id);links.require(c,kind(before.data()),InterventionAuthorization.Action.CONTINUE_EXISTING);
        required(request.evidence(),"EVIDENCE_REQUIRED");if(request.occurredAt()==null||request.outcome()==null||!Set.of("ACKNOWLEDGED","DELIVERED","FAILED","PRINTED","UNKNOWN").contains(request.outcome()))fail("INVALID_DELIVERY_EVIDENCE");
        return journal.execute(c,"prescription-delivery/"+id,key,request,PrescriptionRecord.class,()->{
            links.lock(c,patient,before.encounterId());var current=get(c,patient,id);version(current,request.expectedVersion());
            if(current.authorizedAt()==null)fail("AUTHORIZED_SOURCE_REQUIRED");
            c.jdbc().sql("INSERT INTO prescription_evidence(practice_id,id,evidence_id,source_version,category,outcome,evidence,occurred_at,recorded_by) VALUES(:p,:id,:event,:v,'DELIVERY',:outcome,:evidence,:occurred,:a)")
                .param("p",c.practiceId()).param("id",id).param("event",UUID.randomUUID()).param("v",current.version()+1).param("outcome",request.outcome()).param("evidence",request.evidence()).param("occurred",request.occurredAt()).param("a",c.actorId()).update();
            c.jdbc().sql("UPDATE prescription SET delivery_state=:outcome,version=version+1 WHERE practice_id=:p AND id=:id")
                .param("outcome",request.outcome()).param("p",c.practiceId()).param("id",id).update();
            revision(c,id,current.version()+1,current.data(),request.evidence());var result=get(c,patient,id);publish(c,result);
            journal.audit(c,id,"PRESCRIPTION_DELIVERY_RECORDED",null);return result;
        });
    }
}
