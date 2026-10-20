package ph.epr.api.clinicalchart.infrastructure;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ph.epr.api.clinicalchart.ClinicalLists;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.Action;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.SourceKind;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
public class JdbcClinicalLists implements ClinicalLists {
    private static final Set<String> ALLERGY_STATUSES = Set.of("ACTIVE", "INACTIVE", "RESOLVED", "REFUTED", "ENTERED_IN_ERROR");
    private static final Set<String> MEDICATION_STATUSES = Set.of("ACTIVE", "ON_HOLD", "STOPPED", "COMPLETED", "UNCERTAIN", "ENTERED_IN_ERROR");
    private static final Set<String> PROBLEM_STATUSES = Set.of("ACTIVE", "INACTIVE", "RESOLVED", "REFUTED", "ENTERED_IN_ERROR");
    private final ClinicalPatientRegistry patients;
    private final ClinicalChartAuthorization authorization;
    private final TenantCommandJournal journal;

    public JdbcClinicalLists(ClinicalPatientRegistry patients, ClinicalChartAuthorization authorization,
                             TenantCommandJournal journal) {
        this.patients = patients; this.authorization = authorization; this.journal = journal;
    }

    @Override
    public ClinicalSummary summary(Context c, UUID patient) {
        patients.requirePatient(c, patient);
        var capabilities = authorization.capabilities(c.actorId(), c.practiceId(), c.siteId());
        return new ClinicalSummary(summary(c, patient, SourceKind.ALLERGY, capabilities),
            summary(c, patient, SourceKind.MEDICATION_USE, capabilities),
            summary(c, patient, SourceKind.PROBLEM, capabilities));
    }

    private ListSummary summary(Context c, UUID patient, SourceKind kind,
                                List<ClinicalChartAuthorization.Capability> capabilities) {
        var capability = capabilities.stream().filter(item -> item.sourceKind() == kind).findFirst();
        var actions = capability.map(item -> new Actions(item.read(), item.propose(), item.write(), item.review()))
            .orElse(new Actions(false, false, false, false));
        if (!actions.read()) return new ListSummary(kind, ListState.UNAVAILABLE, 0, 0, List.of(), null, actions);
        var generation = generation(c, patient, kind, false);
        var previews = previews(c, patient, kind, 5);
        var activeCount = count(c, patient, kind, activeStatuses(kind));
        var review = latestReview(c, patient, kind);
        var state = state(review, generation, activeCount);
        return new ListSummary(kind, state, generation, activeCount, previews, review, actions);
    }

    private ListState state(ReviewManifest review, long generation, long activeCount) {
        if (review == null) return ListState.NOT_REVIEWED;
        if (review.stale() || review.kinds().stream().noneMatch(kind -> kind.sourceGeneration() == generation)) return ListState.STALE;
        var assertion = review.kinds().getFirst().assertion();
        if ((assertion.equals("NONE_KNOWN") || assertion.equals("NO_KNOWN_MEDICATION_ALLERGIES")) && activeCount == 0)
            return ListState.EMPTY_REVIEWED;
        if (assertion.equals("UNABLE_TO_CONFIRM")) return ListState.NOT_REVIEWED;
        return activeCount > 0 ? ListState.AVAILABLE : ListState.NOT_REVIEWED;
    }

    @Override
    public Page<Allergy> allergies(Context c, UUID patient, String status, int page, int pageSize) {
        access(c, patient, SourceKind.ALLERGY, Action.READ); page(page, pageSize);
        var statuses = statuses(status, ALLERGY_STATUSES, Set.of("ACTIVE"));
        var ids = c.jdbc().sql("""
            SELECT allergy_id FROM allergy_record
            WHERE practice_id=:practice AND patient_id=:patient AND clinical_status IN (SELECT unnest(string_to_array(:statuses, ',')))
            ORDER BY created_at DESC, allergy_id LIMIT :limit OFFSET :offset
            """).param("practice",c.practiceId()).param("patient",patient)
            .param("statuses",String.join(",",statuses)).param("limit",pageSize).param("offset",(long)page*pageSize)
            .query(UUID.class).list();
        var total = count(c, patient, SourceKind.ALLERGY, statuses);
        return new Page<>(ids.stream().map(id -> allergyInternal(c, patient, id, true)).toList(), page, pageSize, total);
    }

    @Override public Allergy allergy(Context c, UUID patient, UUID id) {
        access(c, patient, SourceKind.ALLERGY, Action.READ); return allergyInternal(c, patient, id, true);
    }

    @Override public Allergy createAllergy(Context c, UUID patient, UUID key, CreateAllergy command) {
        access(c, patient, SourceKind.ALLERGY, Action.WRITE); validate(command);
        return journal.execute(c, "create-allergy/"+patient, key, command, Allergy.class, () -> {
            if (existsAnyPatient(c, "allergy_record", "allergy_id", command.id())) conflict("SOURCE_ID_ALREADY_USED");
            c.jdbc().sql("""
                INSERT INTO allergy_record(practice_id,patient_id,allergy_id,clinical_status,created_by)
                VALUES(:practice,:patient,:id,'ACTIVE',:actor)
                """).param("practice",c.practiceId()).param("patient",patient).param("id",command.id()).param("actor",c.actorId()).update();
            insertAllergyRevision(c, patient, command.id(), 0, command.data(), "Initial clinical record");
            changed(c, patient, SourceKind.ALLERGY);
            journal.audit(c, command.id(), "ALLERGY_CREATED", null);
            return allergyInternal(c, patient, command.id(), true);
        });
    }

    @Override public Allergy reviseAllergy(Context c, UUID patient, UUID id, UUID key, ReviseAllergy command) {
        access(c, patient, SourceKind.ALLERGY, Action.WRITE); validate(command);
        return journal.execute(c, "revise-allergy/"+id, key, command, Allergy.class, () -> {
            var current = lockHead(c, "allergy_record", "allergy_id", patient, id);
            version(current.version(), command.expectedVersion()); editable(current.status());
            insertAllergyRevision(c, patient, id, current.version()+1, command.data(), command.reason());
            bump(c, "allergy_record", "allergy_id", patient, id, null, null);
            changed(c, patient, SourceKind.ALLERGY); journal.audit(c,id,"ALLERGY_REVISED",command.reason());
            return allergyInternal(c,patient,id,true);
        });
    }

    @Override public Allergy transitionAllergy(Context c, UUID patient, UUID id, UUID key, Transition command) {
        access(c, patient, SourceKind.ALLERGY, Action.WRITE); transition(command);
        return journal.execute(c, "transition-allergy/"+id, key, command, Allergy.class, () -> {
            var current=lockHead(c,"allergy_record","allergy_id",patient,id); version(current.version(),command.expectedVersion());
            var next=allergyTransition(current.status(),command.transition());
            var data=allergyInternal(c,patient,id,false).data();
            insertAllergyRevision(c,patient,id,current.version()+1,data,command.reason());
            bump(c,"allergy_record","allergy_id",patient,id,"clinical_status",next);
            changed(c,patient,SourceKind.ALLERGY);journal.audit(c,id,"ALLERGY_"+command.transition().toUpperCase(Locale.ROOT),command.reason());
            return allergyInternal(c,patient,id,true);
        });
    }

    @Override public ReviewManifest reviewAllergies(Context c, UUID patient, UUID key, ReviewCommand command) {
        return review(c,patient,key,SourceKind.ALLERGY,command);
    }

    private Allergy allergyInternal(Context c, UUID patient, UUID id, boolean history) {
        var head=c.jdbc().sql("SELECT current_version,clinical_status,created_by,created_at FROM allergy_record WHERE practice_id=:p AND patient_id=:patient AND allergy_id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id)
            .query((r,n)->new Object[]{r.getLong(1),r.getString(2),r.getObject(3,UUID.class),r.getObject(4,OffsetDateTime.class)})
            .optional().orElseThrow(()->new ClinicalAccessException("ALLERGY_UNAVAILABLE",404));
        var revisions=allergyRevisions(c,patient,id);
        var current=revisions.stream().filter(revision->revision.version()==(long)head[0]).findFirst().orElseThrow();
        var duplicate=duplicate(c,"allergy_record","allergy_revision","allergy_id","substance_display",patient,id,current.data().substanceDisplay(),"clinical_status='ACTIVE'");
        return new Allergy(id,patient,(long)head[0],(String)head[1],(UUID)head[2],(OffsetDateTime)head[3],current.data(),history?revisions:List.of(),duplicate);
    }

    private List<AllergyRevision> allergyRevisions(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT * FROM allergy_revision WHERE practice_id=:p AND patient_id=:patient AND allergy_id=:id ORDER BY version")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->{
                var version=r.getLong("version");
                var data=new AllergyData(r.getString("substance_display"),code(r),r.getString("category"),r.getString("reaction_type"),
                    r.getString("verification_status"),r.getString("criticality"),r.getString("onset_value"),r.getString("onset_precision"),
                    r.getObject("last_occurrence",OffsetDateTime.class),r.getString("information_source"),r.getString("asserter"),r.getString("notes"),
                    r.getObject("clinical_time",OffsetDateTime.class),r.getObject("encounter_id",UUID.class),reactions(c,patient,id,version));
                return new AllergyRevision(version,data,r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("received_at",OffsetDateTime.class));
            }).list();
    }

    private List<Reaction> reactions(Context c,UUID patient,UUID id,long version) {
        return c.jdbc().sql("SELECT reaction_id,manifestation,severity,onset_value,exposure_route FROM allergy_reaction_revision WHERE practice_id=:p AND patient_id=:patient AND allergy_id=:id AND version=:v ORDER BY reaction_id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).param("v",version)
            .query((r,n)->new Reaction(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5))).list();
    }

    private void insertAllergyRevision(Context c,UUID patient,UUID id,long version,AllergyData data,String reason) {
        validate(data); required(reason,"CORRECTION_REASON_REQUIRED",500);
        c.jdbc().sql("""
            INSERT INTO allergy_revision(practice_id,patient_id,allergy_id,version,substance_display,terminology_system,
              terminology_code,terminology_version,category,reaction_type,verification_status,criticality,onset_value,
              onset_precision,last_occurrence,information_source,asserter,notes,clinical_time,encounter_id,reason,actor_id)
            VALUES(:p,:patient,:id,:v,:display,:system,:code,:codeVersion,:category,:type,:verification,:criticality,:onset,
              :precision,:last,:source,:asserter,:notes,:clinicalTime,:encounter,:reason,:actor)
            """).param("p",c.practiceId()).param("patient",patient).param("id",id).param("v",version)
            .param("display",data.substanceDisplay()).param("system",text(data.code(),Code::system),Types.VARCHAR)
            .param("code",text(data.code(),Code::code),Types.VARCHAR).param("codeVersion",text(data.code(),Code::version),Types.VARCHAR)
            .param("category",data.category()).param("type",data.reactionType()).param("verification",data.verificationStatus())
            .param("criticality",data.criticality()).param("onset",data.onset(),Types.VARCHAR).param("precision",data.onsetPrecision())
            .param("last",data.lastOccurrence(),Types.TIMESTAMP_WITH_TIMEZONE).param("source",data.informationSource())
            .param("asserter",data.asserter(),Types.VARCHAR).param("notes",data.notes(),Types.VARCHAR)
            .param("clinicalTime",data.clinicalTime(),Types.TIMESTAMP_WITH_TIMEZONE).param("encounter",data.encounterId(),Types.OTHER)
            .param("reason",reason.strip()).param("actor",c.actorId()).update();
        for(var reaction:data.reactions()) c.jdbc().sql("""
            INSERT INTO allergy_reaction_revision(practice_id,patient_id,allergy_id,version,reaction_id,manifestation,severity,onset_value,exposure_route)
            VALUES(:p,:patient,:id,:v,:reaction,:manifestation,:severity,:onset,:route)
            """).param("p",c.practiceId()).param("patient",patient).param("id",id).param("v",version)
            .param("reaction",reaction.reactionId()).param("manifestation",reaction.manifestation()).param("severity",reaction.severity())
            .param("onset",reaction.onset(),Types.VARCHAR).param("route",reaction.exposureRoute(),Types.VARCHAR).update();
    }

    @Override public Page<MedicationUse> medicationUse(Context c,UUID patient,String status,int page,int pageSize) {
        access(c,patient,SourceKind.MEDICATION_USE,Action.READ);page(page,pageSize);
        var statuses=statuses(status,MEDICATION_STATUSES,Set.of("ACTIVE","ON_HOLD","UNCERTAIN"));
        var ids=c.jdbc().sql("SELECT medication_use_id FROM medication_use_record WHERE practice_id=:p AND patient_id=:patient AND status IN (SELECT unnest(string_to_array(:statuses, ','))) ORDER BY created_at DESC,medication_use_id LIMIT :limit OFFSET :offset")
            .param("p",c.practiceId()).param("patient",patient).param("statuses",String.join(",",statuses))
            .param("limit",pageSize).param("offset",(long)page*pageSize).query(UUID.class).list();
        return new Page<>(ids.stream().map(id->medicationInternal(c,patient,id,true)).toList(),page,pageSize,count(c,patient,SourceKind.MEDICATION_USE,statuses));
    }

    @Override public MedicationUse medicationUse(Context c,UUID patient,UUID id) {
        access(c,patient,SourceKind.MEDICATION_USE,Action.READ);return medicationInternal(c,patient,id,true);
    }

    @Override public MedicationUse createMedicationUse(Context c,UUID patient,UUID key,CreateMedicationUse command) {
        access(c,patient,SourceKind.MEDICATION_USE,Action.WRITE);validate(command);
        return journal.execute(c,"create-medication-use/"+patient,key,command,MedicationUse.class,()->{
            if(existsAnyPatient(c,"medication_use_record","medication_use_id",command.id()))conflict("SOURCE_ID_ALREADY_USED");
            c.jdbc().sql("INSERT INTO medication_use_record(practice_id,patient_id,medication_use_id,status,created_by) VALUES(:p,:patient,:id,'ACTIVE',:actor)")
                .param("p",c.practiceId()).param("patient",patient).param("id",command.id()).param("actor",c.actorId()).update();
            insertMedicationRevision(c,patient,command.id(),0,command.data(),"Initial clinical record");changed(c,patient,SourceKind.MEDICATION_USE);
            journal.audit(c,command.id(),"MEDICATION_USE_CREATED",null);return medicationInternal(c,patient,command.id(),true);
        });
    }

    @Override public MedicationUse reviseMedicationUse(Context c,UUID patient,UUID id,UUID key,ReviseMedicationUse command) {
        access(c,patient,SourceKind.MEDICATION_USE,Action.WRITE);validate(command);
        return journal.execute(c,"revise-medication-use/"+id,key,command,MedicationUse.class,()->{
            var current=lockHead(c,"medication_use_record","medication_use_id",patient,id);version(current.version(),command.expectedVersion());editable(current.status());
            insertMedicationRevision(c,patient,id,current.version()+1,command.data(),command.reason());
            bump(c,"medication_use_record","medication_use_id",patient,id,null,null);changed(c,patient,SourceKind.MEDICATION_USE);
            journal.audit(c,id,"MEDICATION_USE_REVISED",command.reason());return medicationInternal(c,patient,id,true);
        });
    }

    @Override public MedicationUse transitionMedicationUse(Context c,UUID patient,UUID id,UUID key,Transition command) {
        access(c,patient,SourceKind.MEDICATION_USE,Action.WRITE);transition(command);
        return journal.execute(c,"transition-medication-use/"+id,key,command,MedicationUse.class,()->{
            var current=lockHead(c,"medication_use_record","medication_use_id",patient,id);version(current.version(),command.expectedVersion());
            var next=medicationTransition(current.status(),command.transition());var data=medicationInternal(c,patient,id,false).data();
            insertMedicationRevision(c,patient,id,current.version()+1,data,command.reason());bump(c,"medication_use_record","medication_use_id",patient,id,"status",next);
            changed(c,patient,SourceKind.MEDICATION_USE);journal.audit(c,id,"MEDICATION_USE_"+command.transition().toUpperCase(Locale.ROOT),command.reason());
            return medicationInternal(c,patient,id,true);
        });
    }

    @Override public ReviewManifest reviewMedications(Context c,UUID patient,UUID key,ReviewCommand command) {
        return review(c,patient,key,SourceKind.MEDICATION_USE,command);
    }

    private MedicationUse medicationInternal(Context c,UUID patient,UUID id,boolean history) {
        var head=c.jdbc().sql("SELECT current_version,status,created_by,created_at FROM medication_use_record WHERE practice_id=:p AND patient_id=:patient AND medication_use_id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id)
            .query((r,n)->new Object[]{r.getLong(1),r.getString(2),r.getObject(3,UUID.class),r.getObject(4,OffsetDateTime.class)})
            .optional().orElseThrow(()->new ClinicalAccessException("MEDICATION_USE_UNAVAILABLE",404));
        var revisions=medicationRevisions(c,patient,id);var current=revisions.stream().filter(x->x.version()==(long)head[0]).findFirst().orElseThrow();
        var duplicate=duplicate(c,"medication_use_record","medication_use_revision","medication_use_id","medication_display",patient,id,current.data().medicationDisplay(),"status IN ('ACTIVE','ON_HOLD','UNCERTAIN')");
        return new MedicationUse(id,patient,(long)head[0],(String)head[1],(UUID)head[2],(OffsetDateTime)head[3],current.data(),history?revisions:List.of(),duplicate);
    }

    private List<MedicationUseRevision> medicationRevisions(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT * FROM medication_use_revision WHERE practice_id=:p AND patient_id=:patient AND medication_use_id=:id ORDER BY version")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new MedicationUseRevision(r.getLong("version"),
                new MedicationUseData(r.getString("medication_display"),code(r),r.getString("brand"),r.getString("strength"),r.getString("formulation"),r.getBigDecimal("dose"),r.getString("dose_unit"),r.getString("route"),r.getString("frequency"),r.getBoolean("prn"),r.getString("effective_start"),r.getString("effective_end"),r.getString("effective_precision"),r.getString("information_source"),r.getString("prescriber"),r.getString("adherence"),r.getString("notes"),r.getObject("indication_problem_id",UUID.class),r.getObject("prescription_id",UUID.class),r.getObject("clinical_time",OffsetDateTime.class),r.getObject("encounter_id",UUID.class)),
                r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("received_at",OffsetDateTime.class))).list();
    }

    private void insertMedicationRevision(Context c,UUID patient,UUID id,long version,MedicationUseData data,String reason) {
        validate(data);required(reason,"CORRECTION_REASON_REQUIRED",500);
        c.jdbc().sql("""
            INSERT INTO medication_use_revision(practice_id,patient_id,medication_use_id,version,medication_display,terminology_system,
              terminology_code,terminology_version,brand,strength,formulation,dose,dose_unit,route,frequency,prn,effective_start,effective_end,
              effective_precision,information_source,prescriber,adherence,notes,indication_problem_id,prescription_id,clinical_time,encounter_id,reason,actor_id)
            VALUES(:p,:patient,:id,:v,:display,:system,:code,:codeVersion,:brand,:strength,:formulation,:dose,:doseUnit,:route,:frequency,:prn,
              :effectiveStart,:effectiveEnd,:precision,:source,:prescriber,:adherence,:notes,:problem,:prescription,:clinicalTime,:encounter,:reason,:actor)
            """).param("p",c.practiceId()).param("patient",patient).param("id",id).param("v",version).param("display",data.medicationDisplay())
            .param("system",text(data.code(),Code::system),Types.VARCHAR).param("code",text(data.code(),Code::code),Types.VARCHAR)
            .param("codeVersion",text(data.code(),Code::version),Types.VARCHAR).param("brand",data.brand(),Types.VARCHAR).param("strength",data.strength(),Types.VARCHAR)
            .param("formulation",data.formulation(),Types.VARCHAR).param("dose",data.dose(),Types.NUMERIC).param("doseUnit",data.doseUnit(),Types.VARCHAR)
            .param("route",data.route(),Types.VARCHAR).param("frequency",data.frequency(),Types.VARCHAR).param("prn",data.prn())
            .param("effectiveStart",data.effectiveStart(),Types.VARCHAR).param("effectiveEnd",data.effectiveEnd(),Types.VARCHAR).param("precision",data.effectivePrecision())
            .param("source",data.informationSource()).param("prescriber",data.prescriber(),Types.VARCHAR).param("adherence",data.adherence())
            .param("notes",data.notes(),Types.VARCHAR).param("problem",data.indicationProblemId(),Types.OTHER).param("prescription",data.prescriptionId(),Types.OTHER)
            .param("clinicalTime",data.clinicalTime(),Types.TIMESTAMP_WITH_TIMEZONE).param("encounter",data.encounterId(),Types.OTHER)
            .param("reason",reason.strip()).param("actor",c.actorId()).update();
    }

    @Override public Page<Problem> problems(Context c,UUID patient,String status,int page,int pageSize) {
        access(c,patient,SourceKind.PROBLEM,Action.READ);page(page,pageSize);var statuses=statuses(status,PROBLEM_STATUSES,Set.of("ACTIVE"));
        var ids=c.jdbc().sql("SELECT problem_id FROM problem_record WHERE practice_id=:p AND patient_id=:patient AND clinical_status IN (SELECT unnest(string_to_array(:statuses, ','))) ORDER BY priority,created_at DESC,problem_id LIMIT :limit OFFSET :offset")
            .param("p",c.practiceId()).param("patient",patient).param("statuses",String.join(",",statuses)).param("limit",pageSize).param("offset",(long)page*pageSize).query(UUID.class).list();
        return new Page<>(ids.stream().map(id->problemInternal(c,patient,id,true)).toList(),page,pageSize,count(c,patient,SourceKind.PROBLEM,statuses));
    }

    @Override public Problem problem(Context c,UUID patient,UUID id) { access(c,patient,SourceKind.PROBLEM,Action.READ);return problemInternal(c,patient,id,true); }

    @Override public Problem createProblem(Context c,UUID patient,UUID key,CreateProblem command) {
        access(c,patient,SourceKind.PROBLEM,Action.WRITE);validate(command);
        return journal.execute(c,"create-problem/"+patient,key,command,Problem.class,()->{
            if(existsAnyPatient(c,"problem_record","problem_id",command.id()))conflict("SOURCE_ID_ALREADY_USED");
            if(problemDuplicate(c,patient,null,command.data()))conflict("DUPLICATE_PROBLEM_REVIEW_REQUIRED");
            c.jdbc().sql("INSERT INTO problem_record(practice_id,patient_id,problem_id,clinical_status,priority,created_by) VALUES(:p,:patient,:id,'ACTIVE',:priority,:actor)")
                .param("p",c.practiceId()).param("patient",patient).param("id",command.id()).param("priority",command.priority()).param("actor",c.actorId()).update();
            insertProblemRevision(c,patient,command.id(),0,command.data(),"Initial clinical record");changed(c,patient,SourceKind.PROBLEM);
            journal.audit(c,command.id(),"PROBLEM_CREATED",null);return problemInternal(c,patient,command.id(),true);
        });
    }

    @Override public Problem reviseProblem(Context c,UUID patient,UUID id,UUID key,ReviseProblem command) {
        access(c,patient,SourceKind.PROBLEM,Action.WRITE);validate(command);
        return journal.execute(c,"revise-problem/"+id,key,command,Problem.class,()->{
            var current=lockHead(c,"problem_record","problem_id",patient,id);version(current.version(),command.expectedVersion());editable(current.status());
            if(problemDuplicate(c,patient,id,command.data()))conflict("DUPLICATE_PROBLEM_REVIEW_REQUIRED");
            insertProblemRevision(c,patient,id,current.version()+1,command.data(),command.reason());
            bump(c,"problem_record","problem_id",patient,id,command.priority()==null?null:"priority",command.priority());changed(c,patient,SourceKind.PROBLEM);
            journal.audit(c,id,"PROBLEM_REVISED",command.reason());return problemInternal(c,patient,id,true);
        });
    }

    @Override public Problem transitionProblem(Context c,UUID patient,UUID id,UUID key,Transition command) {
        access(c,patient,SourceKind.PROBLEM,Action.WRITE);transition(command);
        return journal.execute(c,"transition-problem/"+id,key,command,Problem.class,()->{
            var current=lockHead(c,"problem_record","problem_id",patient,id);version(current.version(),command.expectedVersion());
            var next=problemTransition(current.status(),command.transition());var data=problemInternal(c,patient,id,false).data();
            insertProblemRevision(c,patient,id,current.version()+1,data,command.reason());bump(c,"problem_record","problem_id",patient,id,"clinical_status",next);
            changed(c,patient,SourceKind.PROBLEM);journal.audit(c,id,"PROBLEM_"+command.transition().toUpperCase(Locale.ROOT),command.reason());return problemInternal(c,patient,id,true);
        });
    }

    @Override public Problem linkProblem(Context c,UUID patient,UUID id,UUID key,EncounterLink command) {
        access(c,patient,SourceKind.PROBLEM,Action.WRITE);if(command==null||command.encounterId()==null)invalid("ENCOUNTER_ID_REQUIRED");required(command.reason(),"LINK_REASON_REQUIRED",500);
        return journal.execute(c,"link-problem/"+id,key,command,Problem.class,()->{
            problemInternal(c,patient,id,false);
            c.jdbc().sql("INSERT INTO problem_encounter_link(practice_id,patient_id,problem_id,encounter_id,linked_by) VALUES(:p,:patient,:id,:encounter,:actor) ON CONFLICT DO NOTHING")
                .param("p",c.practiceId()).param("patient",patient).param("id",id).param("encounter",command.encounterId()).param("actor",c.actorId()).update();
            journal.audit(c,id,"PROBLEM_LINKED_TO_ENCOUNTER",command.reason());return problemInternal(c,patient,id,true);
        });
    }

    @Override public ReviewManifest reviewProblems(Context c,UUID patient,UUID key,ReviewCommand command) { return review(c,patient,key,SourceKind.PROBLEM,command); }

    private Problem problemInternal(Context c,UUID patient,UUID id,boolean history) {
        var head=c.jdbc().sql("SELECT current_version,clinical_status,priority,created_by,created_at FROM problem_record WHERE practice_id=:p AND patient_id=:patient AND problem_id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new Object[]{r.getLong(1),r.getString(2),r.getInt(3),r.getObject(4,UUID.class),r.getObject(5,OffsetDateTime.class)})
            .optional().orElseThrow(()->new ClinicalAccessException("PROBLEM_UNAVAILABLE",404));
        var revisions=problemRevisions(c,patient,id);var current=revisions.stream().filter(x->x.version()==(long)head[0]).findFirst().orElseThrow();
        var links=c.jdbc().sql("SELECT encounter_id FROM problem_encounter_link WHERE practice_id=:p AND patient_id=:patient AND problem_id=:id ORDER BY linked_at")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query(UUID.class).list();
        return new Problem(id,patient,(long)head[0],(String)head[1],(int)head[2],(UUID)head[3],(OffsetDateTime)head[4],current.data(),history?revisions:List.of(),links,problemDuplicate(c,patient,id,current.data()));
    }

    private List<ProblemRevision> problemRevisions(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT * FROM problem_revision WHERE practice_id=:p AND patient_id=:patient AND problem_id=:id ORDER BY version")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new ProblemRevision(r.getLong("version"),
                new ProblemData(r.getString("problem_display"),code(r),r.getString("verification_status"),r.getString("onset_value"),r.getString("onset_precision"),r.getString("abatement_value"),r.getString("abatement_precision"),r.getString("severity"),r.getString("information_source"),r.getString("notes"),r.getObject("clinical_time",OffsetDateTime.class),r.getObject("encounter_id",UUID.class)),
                r.getString("reason"),r.getObject("actor_id",UUID.class),r.getObject("received_at",OffsetDateTime.class))).list();
    }

    private void insertProblemRevision(Context c,UUID patient,UUID id,long version,ProblemData data,String reason) {
        validate(data);required(reason,"CORRECTION_REASON_REQUIRED",500);
        c.jdbc().sql("""
            INSERT INTO problem_revision(practice_id,patient_id,problem_id,version,problem_display,terminology_system,terminology_code,terminology_version,
              verification_status,onset_value,onset_precision,abatement_value,abatement_precision,severity,information_source,notes,clinical_time,encounter_id,reason,actor_id)
            VALUES(:p,:patient,:id,:v,:display,:system,:code,:codeVersion,:verification,:onset,:onsetPrecision,:abatement,:abatementPrecision,
              :severity,:source,:notes,:clinicalTime,:encounter,:reason,:actor)
            """).param("p",c.practiceId()).param("patient",patient).param("id",id).param("v",version).param("display",data.problemDisplay())
            .param("system",text(data.code(),Code::system),Types.VARCHAR).param("code",text(data.code(),Code::code),Types.VARCHAR).param("codeVersion",text(data.code(),Code::version),Types.VARCHAR)
            .param("verification",data.verificationStatus()).param("onset",data.onset(),Types.VARCHAR).param("onsetPrecision",data.onsetPrecision())
            .param("abatement",data.abatement(),Types.VARCHAR).param("abatementPrecision",data.abatementPrecision()).param("severity",data.severity(),Types.VARCHAR)
            .param("source",data.informationSource()).param("notes",data.notes(),Types.VARCHAR).param("clinicalTime",data.clinicalTime(),Types.TIMESTAMP_WITH_TIMEZONE)
            .param("encounter",data.encounterId(),Types.OTHER).param("reason",reason.strip()).param("actor",c.actorId()).update();
    }

    @Override public Proposal propose(Context c,UUID patient,UUID key,Propose command) {
        if(command==null||command.proposalId()==null||command.sourceKind()==null||command.payload()==null)invalid("INVALID_PROPOSAL");
        access(c,patient,command.sourceKind(),Action.PROPOSE);required(command.informationSource(),"INFORMATION_SOURCE_REQUIRED",200);
        if(!Set.of("ADD","UPDATE","STOP","RESOLVE").contains(command.proposedAction()))invalid("INVALID_PROPOSED_ACTION");
        if(!command.proposedAction().equals("ADD")&&command.targetId()==null)invalid("PROPOSAL_TARGET_REQUIRED");
        return journal.execute(c,"propose-clinical-record/"+patient,key,command,Proposal.class,()->{
            c.jdbc().sql("""
                INSERT INTO clinical_record_proposal(practice_id,patient_id,proposal_id,site_id,source_kind,proposed_action,target_id,payload,information_source,proposed_by)
                VALUES(:p,:patient,:id,:site,:kind,:action,:target,CAST(:payload AS jsonb),:source,:actor)
                """).param("p",c.practiceId()).param("patient",patient).param("id",command.proposalId()).param("site",c.siteId())
                .param("kind",command.sourceKind().name()).param("action",command.proposedAction()).param("target",command.targetId(),Types.OTHER)
                .param("payload",ClinicalJson.write(command.payload())).param("source",command.informationSource().strip()).param("actor",c.actorId()).update();
            journal.audit(c,command.proposalId(),"CLINICAL_RECORD_PROPOSED",null);
            return proposal(c,patient,command.proposalId());
        });
    }

    @Override public List<Proposal> proposals(Context c,UUID patient) {
        patients.requirePatient(c,patient);var readable=authorization.capabilities(c.actorId(),c.practiceId(),c.siteId()).stream()
            .filter(capability->capability.read()).map(capability->capability.sourceKind().name()).toList();
        if(readable.isEmpty())return List.of();
        return c.jdbc().sql("""
            SELECT proposal.*,decision.decision,decision.reason AS decision_reason
            FROM clinical_record_proposal proposal
            LEFT JOIN proposal_decision decision USING(practice_id,patient_id,proposal_id)
            WHERE proposal.practice_id=:p AND proposal.patient_id=:patient
              AND proposal.source_kind IN (SELECT unnest(string_to_array(:kinds, ',')))
            ORDER BY proposal.proposed_at DESC
            """).param("p",c.practiceId()).param("patient",patient).param("kinds",String.join(",",readable))
            .query((r,n)->proposal(r)).list();
    }

    @Override public ReviewManifest reconcile(Context c,UUID patient,UUID key,Reconcile command) {
        if(command==null||command.reviewId()==null||command.encounterId()==null||command.expectedGenerations()==null||command.decisions()==null)
            invalid("INVALID_RECONCILIATION");
        for(var kind:SourceKind.values()) { access(c,patient,kind,Action.REVIEW);if(!command.expectedGenerations().containsKey(kind))invalid("EXPECTED_LIST_GENERATIONS_REQUIRED"); }
        required(command.source(),"REVIEW_SOURCE_REQUIRED",2000);
        return journal.execute(c,"reconcile-clinical-lists/"+patient,key,command,ReviewManifest.class,()->{
            for(var kind:SourceKind.values())if(generation(c,patient,kind,true)!=command.expectedGenerations().get(kind))conflict("RECONCILIATION_CONFLICT");
            for(var decision:command.decisions()) apply(c,patient,command.reviewId(),decision);
            var review=createReview(c,patient,command.reviewId(),"RECONCILIATION",command.encounterId(),"RECONCILED",command.source(),command.interactionsUnavailableAcknowledged(),command.clientTime(),List.of(SourceKind.ALLERGY,SourceKind.MEDICATION_USE,SourceKind.PROBLEM),command.decisions());
            for(var decision:command.decisions()) if(decision.proposalId()!=null) {
                var normalized=switch(decision.decision()) { case "REJECT"->"REJECTED";case "UPDATE"->"EDITED_AND_ACCEPTED";default->"ACCEPTED"; };
                c.jdbc().sql("INSERT INTO proposal_decision(practice_id,patient_id,proposal_id,decision,reason,decided_by,review_id) VALUES(:p,:patient,:proposal,:decision,:reason,:actor,:review)")
                    .param("p",c.practiceId()).param("patient",patient).param("proposal",decision.proposalId()).param("decision",normalized)
                    .param("reason",decision.reason()).param("actor",c.actorId()).param("review",review.reviewId()).update();
            }
            journal.audit(c,review.reviewId(),"CLINICAL_LISTS_RECONCILED",null);return review;
        });
    }

    private void apply(Context c,UUID patient,UUID reviewId,ReconciliationDecision decision) {
        if(decision==null||decision.sourceKind()==null||decision.decision()==null)invalid("INVALID_RECONCILIATION_DECISION");
        required(decision.reason(),"DECISION_REASON_REQUIRED",500);access(c,patient,decision.sourceKind(),Action.WRITE);
        Proposal proposal=null;
        if(decision.proposalId()!=null) {
            proposal=proposal(c,patient,decision.proposalId());
            if(proposal.decision()!=null)conflict("PROPOSAL_ALREADY_DECIDED");
            if(proposal.sourceKind()!=decision.sourceKind())invalid("PROPOSAL_KIND_MISMATCH");
        }
        if(decision.decision().equals("REJECT")||decision.decision().equals("KEEP"))return;
        var payload=decision.replacement()!=null?decision.replacement():proposal==null?null:proposal.payload();
        var sourceId=decision.sourceId()!=null?decision.sourceId():proposal==null?null:proposal.targetId();
        var nestedKey=UUID.nameUUIDFromBytes((reviewId+"/"+decision.sourceKind()+"/"+(decision.proposalId()==null?sourceId:decision.proposalId())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(decision.decision().equals("ADD")) {
            switch(decision.sourceKind()) {
                case ALLERGY -> createAllergy(c,patient,nestedKey,read(payload,CreateAllergy.class));
                case MEDICATION_USE -> createMedicationUse(c,patient,nestedKey,read(payload,CreateMedicationUse.class));
                case PROBLEM -> createProblem(c,patient,nestedKey,read(payload,CreateProblem.class));
            }
            return;
        }
        if(sourceId==null)invalid("RECONCILIATION_SOURCE_REQUIRED");
        if(decision.decision().equals("UPDATE")) {
            switch(decision.sourceKind()) {
                case ALLERGY -> reviseAllergy(c,patient,sourceId,nestedKey,new ReviseAllergy(decision.expectedVersion(),decision.reason(),read(payload,AllergyData.class)));
                case MEDICATION_USE -> reviseMedicationUse(c,patient,sourceId,nestedKey,new ReviseMedicationUse(decision.expectedVersion(),decision.reason(),read(payload,MedicationUseData.class)));
                case PROBLEM -> reviseProblem(c,patient,sourceId,nestedKey,new ReviseProblem(decision.expectedVersion(),decision.reason(),null,read(payload,ProblemData.class)));
            }
            return;
        }
        var transition=switch(decision.decision()){case "STOP"->"STOP";case "RESOLVE"->"RESOLVE";default->throw new ClinicalAccessException("INVALID_RECONCILIATION_DECISION",422);};
        switch(decision.sourceKind()) {
            case ALLERGY -> transitionAllergy(c,patient,sourceId,nestedKey,new Transition(decision.expectedVersion(),transition,decision.reason()));
            case MEDICATION_USE -> transitionMedicationUse(c,patient,sourceId,nestedKey,new Transition(decision.expectedVersion(),transition,decision.reason()));
            case PROBLEM -> transitionProblem(c,patient,sourceId,nestedKey,new Transition(decision.expectedVersion(),transition,decision.reason()));
        }
    }

    @Override public List<ReviewManifest> reviews(Context c,UUID patient,UUID encounter) {
        patients.requirePatient(c,patient);var allowed=authorization.capabilities(c.actorId(),c.practiceId(),c.siteId()).stream()
            .filter(ClinicalChartAuthorization.Capability::read).map(ClinicalChartAuthorization.Capability::sourceKind).collect(java.util.stream.Collectors.toSet());
        if(allowed.isEmpty())return List.of();
        var ids=c.jdbc().sql("SELECT review_id FROM clinical_list_review WHERE practice_id=:p AND patient_id=:patient AND (CAST(:encounter AS uuid) IS NULL OR encounter_id=:encounter) ORDER BY reviewed_at DESC LIMIT 200")
            .param("p",c.practiceId()).param("patient",patient).param("encounter",encounter,Types.OTHER).query(UUID.class).list();
        return ids.stream().map(id->manifest(c,patient,id)).filter(review->review.kinds().stream().allMatch(kind->allowed.contains(kind.sourceKind()))).toList();
    }

    @Override public ReviewManifest requireCurrentReview(Context c,UUID patient,UUID reviewId) {
        patients.requirePatient(c,patient);var review=manifest(c,patient,reviewId);
        for(var kind:review.kinds())access(c,patient,kind.sourceKind(),Action.READ);
        if(!review.siteId().equals(c.siteId()))invalid("CLINICAL_LIST_REVIEW_CONTEXT_MISMATCH");
        if(review.stale())conflict("CLINICAL_LIST_REVIEW_STALE");return review;
    }

    @Override public SafetyManifest requireCurrentSafety(Context c,UUID patient,UUID reviewId) {
        var review=requireCurrentReview(c,patient,reviewId);
        if(!review.reviewType().equals("RECONCILIATION")||review.kinds().stream().map(ReviewKind::sourceKind).collect(java.util.stream.Collectors.toSet()).containsAll(Set.of(SourceKind.ALLERGY,SourceKind.MEDICATION_USE))==false)
            invalid("CANONICAL_SAFETY_RECONCILIATION_REQUIRED");
        if(!review.interactionsUnavailableAcknowledged())invalid("INTERACTIONS_UNAVAILABLE_ACKNOWLEDGEMENT_REQUIRED");
        if(review.reviewedAt().isBefore(OffsetDateTime.now().minusHours(24)))invalid("CURRENT_REVIEWED_SAFETY_CONTEXT_REQUIRED");
        var allergyKind=review.kinds().stream().filter(kind->kind.sourceKind()==SourceKind.ALLERGY).findFirst().orElseThrow();
        var allergies=review.items().stream().filter(item->item.sourceKind()==SourceKind.ALLERGY).map(item->{
            var revision=allergyRevisions(c,patient,item.sourceId()).stream().filter(value->value.version()==item.sourceVersion()).findFirst().orElseThrow();
            return new SafetyEntry(item.sourceId(),item.sourceVersion(),revision.data().substanceDisplay(),revision.data().code());
        }).toList();
        var medications=review.items().stream().filter(item->item.sourceKind()==SourceKind.MEDICATION_USE).map(item->{
            var revision=medicationRevisions(c,patient,item.sourceId()).stream().filter(value->value.version()==item.sourceVersion()).findFirst().orElseThrow();
            return new SafetyEntry(item.sourceId(),item.sourceVersion(),revision.data().medicationDisplay(),revision.data().code());
        }).toList();
        return new SafetyManifest(review.reviewId(),review.manifestHash(),review.reviewedAt(),allergyKind.assertion(),allergies,medications,review.interactionsUnavailableAcknowledged());
    }

    private ReviewManifest review(Context c,UUID patient,UUID key,SourceKind kind,ReviewCommand command) {
        access(c,patient,kind,Action.REVIEW);if(command==null||command.reviewId()==null)invalid("REVIEW_ID_REQUIRED");
        required(command.source(),"REVIEW_SOURCE_REQUIRED",2000);validateOutcome(kind,command.outcome());
        return journal.execute(c,"review-"+kind.name().toLowerCase(Locale.ROOT)+"/"+patient,key,command,ReviewManifest.class,()->{
            var review=createReview(c,patient,command.reviewId(),kind.name(),null,command.outcome(),command.source(),command.interactionsUnavailableAcknowledged(),command.clientTime(),List.of(kind),List.of());
            journal.audit(c,review.reviewId(),kind.name()+"_LIST_REVIEWED",null);return review;
        });
    }

    private ReviewManifest createReview(Context c,UUID patient,UUID id,String type,UUID encounter,String outcome,String source,boolean acknowledgement,OffsetDateTime clientTime,List<SourceKind> kinds,List<ReconciliationDecision> decisions) {
        var reviewedAt=OffsetDateTime.now();var reviewKinds=new ArrayList<ReviewKind>();var items=new ArrayList<ReviewItem>();
        for(var kind:kinds) {
            var generation=generation(c,patient,kind,true);var active=count(c,patient,kind,activeStatuses(kind));validateAssertion(c,patient,kind,outcome,active);var assertion=assertion(kind,outcome,active);
            reviewKinds.add(new ReviewKind(kind,generation,assertion));items.addAll(currentItems(c,patient,kind));
        }
        var hash=ClinicalJson.hash(new ManifestEvidence(id,patient,c.siteId(),type,encounter,outcome,source.strip(),acknowledgement,c.actorId(),clientTime,reviewedAt,reviewKinds,items,decisions));
        c.jdbc().sql("""
            INSERT INTO clinical_list_review(practice_id,patient_id,review_id,site_id,review_type,encounter_id,outcome,source,
              interactions_unavailable_acknowledged,reviewed_by,client_time,reviewed_at,manifest_hash)
            VALUES(:p,:patient,:id,:site,:type,:encounter,:outcome,:source,:ack,:actor,:client,:reviewed,:hash)
            """).param("p",c.practiceId()).param("patient",patient).param("id",id).param("site",c.siteId()).param("type",type)
            .param("encounter",encounter,Types.OTHER).param("outcome",outcome).param("source",source.strip()).param("ack",acknowledgement)
            .param("actor",c.actorId()).param("client",clientTime,Types.TIMESTAMP_WITH_TIMEZONE).param("reviewed",reviewedAt).param("hash",hash).update();
        for(var kind:reviewKinds)c.jdbc().sql("INSERT INTO clinical_list_review_kind(practice_id,patient_id,review_id,source_kind,source_generation,assertion) VALUES(:p,:patient,:review,:kind,:generation,:assertion)")
            .param("p",c.practiceId()).param("patient",patient).param("review",id).param("kind",kind.sourceKind().name()).param("generation",kind.sourceGeneration()).param("assertion",kind.assertion()).update();
        for(var item:items)c.jdbc().sql("INSERT INTO clinical_list_review_item(practice_id,patient_id,review_id,source_kind,source_id,source_version,decision) VALUES(:p,:patient,:review,:kind,:source,:version,:decision)")
            .param("p",c.practiceId()).param("patient",patient).param("review",id).param("kind",item.sourceKind().name()).param("source",item.sourceId()).param("version",item.sourceVersion()).param("decision",item.decision()).update();
        for(int index=0;index<decisions.size();index++){
            var decision=decisions.get(index);
            c.jdbc().sql("""
                INSERT INTO clinical_reconciliation_decision(practice_id,patient_id,review_id,decision_order,proposal_id,source_kind,source_id,expected_version,decision,replacement,reason)
                VALUES(:p,:patient,:review,:ordering,:proposal,:kind,:source,:version,:decision,CAST(:replacement AS jsonb),:reason)
                """).param("p",c.practiceId()).param("patient",patient).param("review",id).param("ordering",index)
                .param("proposal",decision.proposalId(),Types.OTHER).param("kind",decision.sourceKind().name()).param("source",decision.sourceId(),Types.OTHER)
                .param("version",decision.expectedVersion()).param("decision",decision.decision()).param("replacement",decision.replacement()==null?"null":decision.replacement().toString()).param("reason",decision.reason()).update();
        }
        return manifest(c,patient,id);
    }

    private ReviewManifest manifest(Context c,UUID patient,UUID id) {
        var row=c.jdbc().sql("SELECT * FROM clinical_list_review WHERE practice_id=:p AND patient_id=:patient AND review_id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new ReviewRow(r.getObject("site_id",UUID.class),r.getString("review_type"),r.getObject("encounter_id",UUID.class),r.getString("outcome"),r.getString("source"),r.getBoolean("interactions_unavailable_acknowledged"),r.getObject("reviewed_by",UUID.class),r.getObject("client_time",OffsetDateTime.class),r.getObject("reviewed_at",OffsetDateTime.class),r.getString("manifest_hash")))
            .optional().orElseThrow(()->new ClinicalAccessException("CLINICAL_LIST_REVIEW_UNAVAILABLE",404));
        var kinds=c.jdbc().sql("SELECT source_kind,source_generation,assertion FROM clinical_list_review_kind WHERE practice_id=:p AND patient_id=:patient AND review_id=:id ORDER BY source_kind")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new ReviewKind(SourceKind.valueOf(r.getString(1)),r.getLong(2),r.getString(3))).list();
        var items=c.jdbc().sql("SELECT source_kind,source_id,source_version,decision FROM clinical_list_review_item WHERE practice_id=:p AND patient_id=:patient AND review_id=:id ORDER BY source_kind,source_id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new ReviewItem(SourceKind.valueOf(r.getString(1)),r.getObject(2,UUID.class),r.getLong(3),r.getString(4))).list();
        var decisions=c.jdbc().sql("SELECT proposal_id,source_kind,source_id,expected_version,decision,replacement::text,reason FROM clinical_reconciliation_decision WHERE practice_id=:p AND patient_id=:patient AND review_id=:id ORDER BY decision_order")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new ReconciliationDecision(r.getObject(1,UUID.class),SourceKind.valueOf(r.getString(2)),r.getObject(3,UUID.class),r.getLong(4),r.getString(5),"null".equals(r.getString(6))?null:ClinicalJson.read(r.getString(6),tools.jackson.databind.JsonNode.class),r.getString(7))).list();
        var stale=kinds.stream().anyMatch(kind->generation(c,patient,kind.sourceKind(),false)!=kind.sourceGeneration());
        return new ReviewManifest(id,patient,row.site(),row.type(),row.encounter(),row.outcome(),row.source(),row.ack(),row.actor(),row.client(),row.reviewed(),row.hash(),kinds,items,decisions,stale);
    }

    @Override public List<Code> terminology(Context c,SourceKind kind,String query) {
        authorization.require(c.actorId(),c.practiceId(),c.siteId(),kind,Action.READ);
        var catalog=switch(kind) {
            case ALLERGY -> List.of(
                new Code("urn:epr:synthetic:allergen","penicillin","2026-10","Penicillin"),
                new Code("urn:epr:synthetic:allergen","amoxicillin","2026-10","Amoxicillin"),
                new Code("urn:epr:synthetic:allergen","peanut","2026-10","Peanut"));
            case MEDICATION_USE -> List.of(
                new Code("urn:epr:synthetic:medication","losartan","2026-10","Losartan"),
                new Code("urn:epr:synthetic:medication","metformin","2026-10","Metformin"),
                new Code("urn:epr:synthetic:medication","amoxicillin","2026-10","Amoxicillin"));
            case PROBLEM -> List.of(
                new Code("urn:epr:synthetic:problem","hypertension","2026-10","Essential hypertension"),
                new Code("urn:epr:synthetic:problem","type-2-diabetes","2026-10","Type 2 diabetes mellitus"),
                new Code("urn:epr:synthetic:problem","asthma","2026-10","Asthma"));
        };
        var normalized=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        return catalog.stream().filter(code->normalized.isEmpty()||code.display().toLowerCase(Locale.ROOT).contains(normalized)||code.code().contains(normalized)).limit(20).toList();
    }

    private List<Preview> previews(Context c,UUID patient,SourceKind kind,int limit) {
        return switch(kind) {
            case ALLERGY -> c.jdbc().sql("""
                SELECT head.allergy_id,head.current_version,revision.substance_display,head.clinical_status,
                       revision.terminology_system,revision.terminology_code,revision.terminology_version
                FROM allergy_record head JOIN allergy_revision revision
                  ON revision.practice_id=head.practice_id AND revision.patient_id=head.patient_id
                 AND revision.allergy_id=head.allergy_id AND revision.version=head.current_version
                WHERE head.practice_id=:p AND head.patient_id=:patient AND head.clinical_status='ACTIVE'
                ORDER BY head.created_at DESC LIMIT :limit
                """).param("p",c.practiceId()).param("patient",patient).param("limit",limit).query((r,n)->new Preview(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getString(4),code(r,5))).list();
            case MEDICATION_USE -> c.jdbc().sql("""
                SELECT head.medication_use_id,head.current_version,revision.medication_display,head.status,
                       revision.terminology_system,revision.terminology_code,revision.terminology_version
                FROM medication_use_record head JOIN medication_use_revision revision
                  ON revision.practice_id=head.practice_id AND revision.patient_id=head.patient_id
                 AND revision.medication_use_id=head.medication_use_id AND revision.version=head.current_version
                WHERE head.practice_id=:p AND head.patient_id=:patient AND head.status IN ('ACTIVE','ON_HOLD','UNCERTAIN')
                ORDER BY head.created_at DESC LIMIT :limit
                """).param("p",c.practiceId()).param("patient",patient).param("limit",limit).query((r,n)->new Preview(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getString(4),code(r,5))).list();
            case PROBLEM -> c.jdbc().sql("""
                SELECT head.problem_id,head.current_version,revision.problem_display,head.clinical_status,
                       revision.terminology_system,revision.terminology_code,revision.terminology_version
                FROM problem_record head JOIN problem_revision revision
                  ON revision.practice_id=head.practice_id AND revision.patient_id=head.patient_id
                 AND revision.problem_id=head.problem_id AND revision.version=head.current_version
                WHERE head.practice_id=:p AND head.patient_id=:patient AND head.clinical_status='ACTIVE'
                ORDER BY head.priority,head.created_at DESC LIMIT :limit
                """).param("p",c.practiceId()).param("patient",patient).param("limit",limit).query((r,n)->new Preview(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getString(4),code(r,5))).list();
        };
    }

    private List<ReviewItem> currentItems(Context c,UUID patient,SourceKind kind) {
        return switch(kind) {
            case ALLERGY -> c.jdbc().sql("SELECT allergy_id,current_version FROM allergy_record WHERE practice_id=:p AND patient_id=:patient AND clinical_status='ACTIVE' ORDER BY allergy_id")
                .param("p",c.practiceId()).param("patient",patient).query((r,n)->new ReviewItem(kind,r.getObject(1,UUID.class),r.getLong(2),"REVIEWED")).list();
            case MEDICATION_USE -> c.jdbc().sql("SELECT medication_use_id,current_version FROM medication_use_record WHERE practice_id=:p AND patient_id=:patient AND status IN ('ACTIVE','ON_HOLD','UNCERTAIN') ORDER BY medication_use_id")
                .param("p",c.practiceId()).param("patient",patient).query((r,n)->new ReviewItem(kind,r.getObject(1,UUID.class),r.getLong(2),"REVIEWED")).list();
            case PROBLEM -> c.jdbc().sql("SELECT problem_id,current_version FROM problem_record WHERE practice_id=:p AND patient_id=:patient AND clinical_status='ACTIVE' ORDER BY problem_id")
                .param("p",c.practiceId()).param("patient",patient).query((r,n)->new ReviewItem(kind,r.getObject(1,UUID.class),r.getLong(2),"REVIEWED")).list();
        };
    }

    private ReviewManifest latestReview(Context c,UUID patient,SourceKind kind) {
        var id=c.jdbc().sql("""
            SELECT review.review_id FROM clinical_list_review review
            JOIN clinical_list_review_kind kind USING(practice_id,patient_id,review_id)
            WHERE review.practice_id=:p AND review.patient_id=:patient AND kind.source_kind=:kind
            ORDER BY review.reviewed_at DESC LIMIT 1
            """).param("p",c.practiceId()).param("patient",patient).param("kind",kind.name()).query(UUID.class).optional();
        return id.map(value->manifest(c,patient,value)).orElse(null);
    }

    private long generation(Context c,UUID patient,SourceKind kind,boolean lock) {
        if(lock)c.jdbc().sql("INSERT INTO clinical_list_state(practice_id,patient_id,source_kind) VALUES(:p,:patient,:kind) ON CONFLICT DO NOTHING")
            .param("p",c.practiceId()).param("patient",patient).param("kind",kind.name()).update();
        return c.jdbc().sql("SELECT generation FROM clinical_list_state WHERE practice_id=:p AND patient_id=:patient AND source_kind=:kind"+(lock?" FOR UPDATE":""))
            .param("p",c.practiceId()).param("patient",patient).param("kind",kind.name()).query(Long.class).optional().orElse(0L);
    }

    private void changed(Context c,UUID patient,SourceKind kind) {
        generation(c,patient,kind,true);
        c.jdbc().sql("UPDATE clinical_list_state SET generation=generation+1 WHERE practice_id=:p AND patient_id=:patient AND source_kind=:kind")
            .param("p",c.practiceId()).param("patient",patient).param("kind",kind.name()).update();
    }

    private long count(Context c,UUID patient,SourceKind kind,Set<String> statuses) {
        var sql=switch(kind){case ALLERGY->"SELECT count(*) FROM allergy_record WHERE practice_id=:p AND patient_id=:patient AND clinical_status IN (SELECT unnest(string_to_array(:statuses, ',')))";
            case MEDICATION_USE->"SELECT count(*) FROM medication_use_record WHERE practice_id=:p AND patient_id=:patient AND status IN (SELECT unnest(string_to_array(:statuses, ',')))";
            case PROBLEM->"SELECT count(*) FROM problem_record WHERE practice_id=:p AND patient_id=:patient AND clinical_status IN (SELECT unnest(string_to_array(:statuses, ',')))";};
        return c.jdbc().sql(sql).param("p",c.practiceId()).param("patient",patient).param("statuses",String.join(",",statuses)).query(Long.class).single();
    }

    private Set<String> activeStatuses(SourceKind kind) { return switch(kind){case ALLERGY,PROBLEM->Set.of("ACTIVE");case MEDICATION_USE->Set.of("ACTIVE","ON_HOLD","UNCERTAIN");}; }
    private Set<String> statuses(String requested,Set<String> allowed,Set<String> defaults) {
        if(requested==null||requested.isBlank()||requested.equalsIgnoreCase("active"))return defaults;
        if(requested.equalsIgnoreCase("history")||requested.equalsIgnoreCase("all"))return allowed;
        var result=new TreeSet<String>();for(var value:requested.split(",")){var normalized=value.strip().toUpperCase(Locale.ROOT);if(!allowed.contains(normalized))invalid("INVALID_STATUS_FILTER");result.add(normalized);}return result;
    }

    private void access(Context c,UUID patient,SourceKind kind,Action action) { patients.requirePatient(c,patient);authorization.require(c.actorId(),c.practiceId(),c.siteId(),kind,action); }
    private Head lockHead(Context c,String table,String idColumn,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT current_version,"+(table.equals("medication_use_record")?"status":"clinical_status")+" FROM "+table+" WHERE practice_id=:p AND patient_id=:patient AND "+idColumn+"=:id FOR UPDATE")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->new Head(r.getLong(1),r.getString(2)))
            .optional().orElseThrow(()->new ClinicalAccessException("CLINICAL_RECORD_UNAVAILABLE",404));
    }
    private void bump(Context c,String table,String idColumn,UUID patient,UUID id,String column,Object value) {
        var sql="UPDATE "+table+" SET current_version=current_version+1"+(column==null?"":","+column+"=:value")+" WHERE practice_id=:p AND patient_id=:patient AND "+idColumn+"=:id";
        var statement=c.jdbc().sql(sql).param("p",c.practiceId()).param("patient",patient).param("id",id);if(column!=null)statement.param("value",value);statement.update();
    }
    private boolean existsAnyPatient(Context c,String table,String idColumn,UUID id) { return c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE practice_id=:p AND "+idColumn+"=:id)").param("p",c.practiceId()).param("id",id).query(Boolean.class).single(); }
    private boolean duplicate(Context c,String headTable,String revisionTable,String idColumn,String displayColumn,UUID patient,UUID id,String display,String activeClause) {
        return c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM "+headTable+" head JOIN "+revisionTable+" revision ON revision.practice_id=head.practice_id AND revision.patient_id=head.patient_id AND revision."+idColumn+"=head."+idColumn+" AND revision.version=head.current_version WHERE head.practice_id=:p AND head.patient_id=:patient AND head."+idColumn+"<>:id AND "+activeClause+" AND lower(trim(revision."+displayColumn+"))=lower(trim(:display)))")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).param("display",display).query(Boolean.class).single();
    }
    private boolean problemDuplicate(Context c,UUID patient,UUID id,ProblemData data) {
        var excluded=id==null?UUID.randomUUID():id;return duplicate(c,"problem_record","problem_revision","problem_id","problem_display",patient,excluded,data.problemDisplay(),"clinical_status='ACTIVE'");
    }
    private Proposal proposal(Context c,UUID patient,UUID id) {
        return c.jdbc().sql("SELECT proposal.*,decision.decision,decision.reason AS decision_reason FROM clinical_record_proposal proposal LEFT JOIN proposal_decision decision USING(practice_id,patient_id,proposal_id) WHERE proposal.practice_id=:p AND proposal.patient_id=:patient AND proposal.proposal_id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("id",id).query((r,n)->proposal(r)).optional().orElseThrow(()->new ClinicalAccessException("PROPOSAL_UNAVAILABLE",404));
    }
    private Proposal proposal(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Proposal(r.getObject("proposal_id",UUID.class),r.getObject("patient_id",UUID.class),r.getObject("site_id",UUID.class),SourceKind.valueOf(r.getString("source_kind")),r.getString("proposed_action"),r.getObject("target_id",UUID.class),
            ClinicalJson.read(r.getString("payload"),tools.jackson.databind.JsonNode.class),r.getString("information_source"),r.getObject("proposed_by",UUID.class),r.getObject("proposed_at",OffsetDateTime.class),r.getString("decision"),r.getString("decision_reason"));
    }
    private Code code(java.sql.ResultSet r) throws java.sql.SQLException { var system=r.getString("terminology_system");return system==null?null:new Code(system,r.getString("terminology_code"),r.getString("terminology_version"),null); }
    private Code code(java.sql.ResultSet r,int start) throws java.sql.SQLException { var system=r.getString(start);return system==null?null:new Code(system,r.getString(start+1),r.getString(start+2),r.getString(3)); }
    private String assertion(SourceKind kind,String outcome,long active) {
        if(outcome.equals("UNABLE_TO_CONFIRM"))return "UNABLE_TO_CONFIRM";
        if(outcome.equals("REVIEWED_NO_KNOWN_MEDICATION_ALLERGIES"))return "NO_KNOWN_MEDICATION_ALLERGIES";
        if(outcome.equals("REVIEWED_NONE_KNOWN"))return "NONE_KNOWN";
        return active==0?"NONE_KNOWN":"KNOWN_VALUES";
    }
    private void validateAssertion(Context c,UUID patient,SourceKind kind,String outcome,long active){
        if(outcome.equals("REVIEWED_NONE_KNOWN")&&active>0)conflict("CONFIRMED_ABSENCE_CONFLICT");
        if(outcome.equals("REVIEWED_KNOWN")&&active==0)conflict("KNOWN_VALUES_REQUIRED");
        if(kind==SourceKind.ALLERGY&&outcome.equals("REVIEWED_NO_KNOWN_MEDICATION_ALLERGIES")){
            var medicationAllergies=c.jdbc().sql("""
                SELECT count(*) FROM allergy_record head JOIN allergy_revision revision
                  ON revision.practice_id=head.practice_id AND revision.patient_id=head.patient_id
                 AND revision.allergy_id=head.allergy_id AND revision.version=head.current_version
                WHERE head.practice_id=:p AND head.patient_id=:patient AND head.clinical_status='ACTIVE' AND revision.category='MEDICATION'
                """).param("p",c.practiceId()).param("patient",patient).query(Long.class).single();
            if(medicationAllergies>0)conflict("NO_KNOWN_MEDICATION_ALLERGIES_CONFLICT");
        }
    }
    private void validateOutcome(SourceKind kind,String outcome) {
        var allowed=new HashSet<>(Set.of("REVIEWED_KNOWN","REVIEWED_NONE_KNOWN","UNABLE_TO_CONFIRM"));if(kind==SourceKind.ALLERGY)allowed.add("REVIEWED_NO_KNOWN_MEDICATION_ALLERGIES");if(!allowed.contains(outcome))invalid("INVALID_REVIEW_OUTCOME");
    }
    private String allergyTransition(String current,String transition) { return transition(current,transition,Map.of("RESOLVE","RESOLVED","INACTIVATE","INACTIVE","REFUTE","REFUTED","REACTIVATE","ACTIVE","ENTER_IN_ERROR","ENTERED_IN_ERROR")); }
    private String medicationTransition(String current,String transition) { return transition(current,transition,Map.of("HOLD","ON_HOLD","STOP","STOPPED","COMPLETE","COMPLETED","REACTIVATE","ACTIVE","ENTER_IN_ERROR","ENTERED_IN_ERROR")); }
    private String problemTransition(String current,String transition) { return transition(current,transition,Map.of("RESOLVE","RESOLVED","INACTIVATE","INACTIVE","REFUTE","REFUTED","REACTIVATE","ACTIVE","ENTER_IN_ERROR","ENTERED_IN_ERROR")); }
    private String transition(String current,String transition,Map<String,String> transitions) {
        var next=transitions.get(transition.toUpperCase(Locale.ROOT));if(next==null)throw new ClinicalAccessException("INVALID_TRANSITION",422);
        if(current.equals("ENTERED_IN_ERROR")||current.equals(next)||next.equals("ACTIVE")&&current.equals("ACTIVE"))conflict("INVALID_STATE_TRANSITION");return next;
    }
    private void editable(String status) { if(status.equals("ENTERED_IN_ERROR"))conflict("ENTERED_IN_ERROR_IMMUTABLE"); }
    private void transition(Transition command) { if(command==null||command.transition()==null)invalid("INVALID_TRANSITION");required(command.reason(),"TRANSITION_REASON_REQUIRED",500); }
    private void validate(CreateAllergy value){if(value==null||value.id()==null)invalid("ALLERGY_ID_REQUIRED");validate(value.data());}
    private void validate(ReviseAllergy value){if(value==null)invalid("INVALID_ALLERGY_REVISION");required(value.reason(),"CORRECTION_REASON_REQUIRED",500);validate(value.data());}
    private void validate(AllergyData value){if(value==null)invalid("ALLERGY_DETAILS_REQUIRED");required(value.substanceDisplay(),"SUBSTANCE_REQUIRED",300);required(value.informationSource(),"INFORMATION_SOURCE_REQUIRED",200);oneOf(value.category(),Set.of("MEDICATION","FOOD","ENVIRONMENT","BIOLOGIC","OTHER"),"INVALID_ALLERGY_CATEGORY");oneOf(value.reactionType(),Set.of("ALLERGY","INTOLERANCE","UNCERTAIN"),"INVALID_REACTION_TYPE");oneOf(value.verificationStatus(),Set.of("UNCONFIRMED","CONFIRMED","REFUTED","ENTERED_IN_ERROR"),"INVALID_VERIFICATION_STATUS");oneOf(value.criticality(),Set.of("LOW","HIGH","UNABLE_TO_ASSESS"),"INVALID_CRITICALITY");precision(value.onsetPrecision());if(value.reactions()==null||value.reactions().size()>50)invalid("INVALID_REACTIONS");var ids=new HashSet<UUID>();for(var reaction:value.reactions()){if(reaction==null||reaction.reactionId()==null||!ids.add(reaction.reactionId()))invalid("UNIQUE_REACTION_ID_REQUIRED");required(reaction.manifestation(),"REACTION_MANIFESTATION_REQUIRED",300);oneOf(reaction.severity(),Set.of("MILD","MODERATE","SEVERE","UNKNOWN"),"INVALID_REACTION_SEVERITY");}code(value.code());}
    private void validate(CreateMedicationUse value){if(value==null||value.id()==null)invalid("MEDICATION_USE_ID_REQUIRED");validate(value.data());}
    private void validate(ReviseMedicationUse value){if(value==null)invalid("INVALID_MEDICATION_USE_REVISION");required(value.reason(),"CORRECTION_REASON_REQUIRED",500);validate(value.data());}
    private void validate(MedicationUseData value){if(value==null)invalid("MEDICATION_USE_DETAILS_REQUIRED");required(value.medicationDisplay(),"MEDICATION_REQUIRED",300);required(value.informationSource(),"INFORMATION_SOURCE_REQUIRED",200);precision(value.effectivePrecision());oneOf(value.adherence(),Set.of("TAKING_AS_REPORTED","TAKING_DIFFERENTLY","NOT_TAKING","UNKNOWN"),"INVALID_ADHERENCE");if((value.dose()==null)!=(value.doseUnit()==null)||value.dose()!=null&&value.dose().signum()<=0)invalid("DOSE_AND_UNIT_REQUIRED");code(value.code());}
    private void validate(CreateProblem value){if(value==null||value.id()==null||value.priority()<0)invalid("INVALID_PROBLEM");validate(value.data());}
    private void validate(ReviseProblem value){if(value==null||value.priority()!=null&&value.priority()<0)invalid("INVALID_PROBLEM_REVISION");required(value.reason(),"CORRECTION_REASON_REQUIRED",500);validate(value.data());}
    private void validate(ProblemData value){if(value==null)invalid("PROBLEM_DETAILS_REQUIRED");required(value.problemDisplay(),"PROBLEM_REQUIRED",300);required(value.informationSource(),"INFORMATION_SOURCE_REQUIRED",200);oneOf(value.verificationStatus(),Set.of("SUSPECTED","CONFIRMED","REFUTED","ENTERED_IN_ERROR"),"INVALID_VERIFICATION_STATUS");precision(value.onsetPrecision());precision(value.abatementPrecision());code(value.code());}
    private void code(Code code){if(code!=null){required(code.system(),"TERMINOLOGY_SYSTEM_REQUIRED",200);required(code.code(),"TERMINOLOGY_CODE_REQUIRED",120);}}
    private void precision(String value){oneOf(value,Set.of("EXACT","MONTH","YEAR","APPROXIMATE","UNKNOWN"),"INVALID_DATE_PRECISION");}
    private void oneOf(String value,Set<String> allowed,String code){if(value==null||!allowed.contains(value))invalid(code);}
    private void page(int page,int size){if(page<0||size<1||size>100)invalid("INVALID_PAGE");}
    private void version(long current,long expected){if(current!=expected)conflict("VERSION_CONFLICT");}
    private static void required(String value,String code,int max){if(value==null||value.isBlank()||value.length()>max)invalid(code);}
    private static void invalid(String code){throw new ClinicalAccessException(code,422);}
    private static void conflict(String code){throw new ClinicalAccessException(code,409);}
    private static <T> String text(T value,java.util.function.Function<T,String> getter){return value==null?null:getter.apply(value);}
    private static <T> T read(tools.jackson.databind.JsonNode node,Class<T> type){if(node==null)invalid("RECONCILIATION_PAYLOAD_REQUIRED");return ClinicalJson.read(node.toString(),type);}
    private record Head(long version,String status) {}
    private record ReviewRow(UUID site,String type,UUID encounter,String outcome,String source,boolean ack,UUID actor,OffsetDateTime client,OffsetDateTime reviewed,String hash) {}
    private record ManifestEvidence(UUID reviewId,UUID patientId,UUID siteId,String reviewType,UUID encounterId,String outcome,String source,boolean acknowledgement,UUID actorId,OffsetDateTime clientTime,OffsetDateTime reviewedAt,List<ReviewKind> kinds,List<ReviewItem> items,List<ReconciliationDecision> decisions) {}
}
