package ph.epr.api.encountertemplates.infrastructure;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterInterventionLinks;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
@Profile("local")
public class JdbcEncounterInterventionLinks implements EncounterInterventionLinks {
    private final ClinicalPatientRegistry patients;
    private final JdbcTemplateRegistry templates;
    private final InterventionAuthorization authorization;
    private final TenantCommandJournal journal;
    public JdbcEncounterInterventionLinks(ClinicalPatientRegistry patients, JdbcTemplateRegistry templates,
        InterventionAuthorization authorization, TenantCommandJournal journal) {
        this.patients=patients; this.templates=templates; this.authorization=authorization; this.journal=journal;
    }
    public EncounterContext acceptedWorkContext(Context c,UUID patient,UUID encounter) { return loadContext(c,patient,encounter,true); }
    public EncounterContext lockAcceptedWork(Context c,UUID patient,UUID encounter) {
        acceptedWorkContext(c,patient,encounter);
        c.jdbc().sql("SELECT encounter_id FROM clinical_encounter WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e FOR UPDATE").param("p",c.practiceId()).param("patient",patient).param("e",encounter).query(UUID.class).single();
        return acceptedWorkContext(c,patient,encounter);
    }
    public EncounterContext context(Context c, UUID patient, UUID encounter) { return loadContext(c,patient,encounter,false); }
    private EncounterContext loadContext(Context c,UUID patient,UUID encounter,boolean acceptedWork) {
        var row=c.jdbc().sql("SELECT site_id,responsible_clinician_id,release_id,care_state,note_state FROM clinical_encounter WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e AND deleted_at IS NULL")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter)
            .query((r,n)->new Row(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5)))
            .optional().orElseThrow(()->new ClinicalAccessException("ENCOUNTER_UNAVAILABLE",404));
        if (!row.site().equals(c.siteId()) || (!acceptedWork && !row.owner().equals(c.actorId()))) throw new ClinicalAccessException("ENCOUNTER_UNAVAILABLE",404);
        var person=acceptedWork && !row.owner().equals(c.actorId())?patients.identityForAcceptedWork(c,patient):patients.requirePatient(c,patient);
        templates.release(c,row.release(),false);
        return new EncounterContext(encounter,patient,row.site(),row.owner(),person.displayName(),person.recordNumber(),row.care(),row.note());
    }
    public EncounterContext lock(Context c, UUID patient, UUID encounter) {
        context(c,patient,encounter);
        c.jdbc().sql("SELECT encounter_id FROM clinical_encounter WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e FOR UPDATE")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).query(UUID.class).single();
        return context(c,patient,encounter);
    }
    public void require(Context c, Kind kind, InterventionAuthorization.Action action) {
        authorization.require(c.actorId(),c.practiceId(),c.siteId(),kind.name(),action);
    }
    public List<InterventionAuthorization.Capability> capabilities(Context c, UUID patient, UUID encounter) {
        context(c,patient,encounter);
        return authorization.capabilities(c.actorId(),c.practiceId(),c.siteId()).stream().filter(InterventionAuthorization.Capability::read).toList();
    }
    public void append(Context c, UUID patient, UUID encounter, Summary summary) {
        lockAcceptedWork(c,patient,encounter);
        if (summary.sourceId()==null || summary.sourceVersion()<0 || summary.ownerId()==null || summary.title()==null
            || summary.title().length()>500 || summary.details().size()>100 || summary.details().stream().anyMatch(d->d==null || d.length()>4000))
            throw new ClinicalAccessException("INVALID_INTERVENTION_SUMMARY",422);
        var existing=c.jdbc().sql("SELECT link_id,patient_id,encounter_id,current_revision FROM encounter_intervention_link WHERE practice_id=:p AND kind=:k AND source_id=:id")
            .param("p",c.practiceId()).param("k",summary.kind().name()).param("id",summary.sourceId())
            .query((r,n)->new Link(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getLong(4))).optional();
        long revision=existing.map(x->x.revision()+1).orElse(0L);
        var linkId=existing.map(Link::id).orElseGet(UUID::randomUUID);
        if (existing.isPresent()) {
            if (!existing.get().patient().equals(patient)||!existing.get().encounter().equals(encounter)) throw new ClinicalAccessException("SOURCE_CONTEXT_MISMATCH",409);
            var previous=c.jdbc().sql("SELECT source_version,checksum FROM encounter_intervention_summary WHERE practice_id=:p AND link_id=:id AND revision=:r")
                .param("p",c.practiceId()).param("id",linkId).param("r",revision-1).query((r,n)->new Previous(r.getLong(1),r.getString(2))).single();
            if (previous.version()==summary.sourceVersion() && previous.hash().equals(ClinicalJson.hash(summary))) return;
            if (previous.version()>=summary.sourceVersion()) throw new ClinicalAccessException("SUMMARY_VERSION_CONFLICT",409);
            c.jdbc().sql("UPDATE encounter_intervention_link SET current_revision=:r WHERE practice_id=:p AND link_id=:id")
                .param("r",revision).param("p",c.practiceId()).param("id",linkId).update();
        } else c.jdbc().sql("INSERT INTO encounter_intervention_link(practice_id,patient_id,encounter_id,link_id,kind,source_id,current_revision,created_by) VALUES(:p,:patient,:e,:id,:k,:source,0,:a)")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).param("id",linkId).param("k",summary.kind().name()).param("source",summary.sourceId()).param("a",c.actorId()).update();
        c.jdbc().sql("INSERT INTO encounter_intervention_summary(practice_id,link_id,revision,source_version,summary,checksum) VALUES(:p,:id,:r,:v,CAST(:s AS jsonb),:hash)")
            .param("p",c.practiceId()).param("id",linkId).param("r",revision).param("v",summary.sourceVersion()).param("s",ClinicalJson.write(summary)).param("hash",ClinicalJson.hash(summary)).update();
        c.jdbc().sql("INSERT INTO encounter_intervention_set(practice_id,patient_id,encounter_id,version) VALUES(:p,:patient,:e,1) ON CONFLICT(practice_id,patient_id,encounter_id) DO UPDATE SET version=encounter_intervention_set.version+1")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).update();
        journal.audit(c,linkId,"INTERVENTION_SUMMARY_APPENDED",null);
    }
    public long version(Context c, UUID patient, UUID encounter) {
        context(c,patient,encounter);
        return c.jdbc().sql("SELECT version FROM encounter_intervention_set WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).query(Long.class).optional().orElse(0L);
    }
    public boolean hasHistory(Context c, UUID patient, UUID encounter) {
        return c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM encounter_intervention_set WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e)")
            .param("p", c.practiceId()).param("patient", patient).param("e", encounter).query(Boolean.class).single();
    }
    private List<Item> all(Context c, UUID patient, UUID encounter) {
        context(c,patient,encounter);
        return c.jdbc().sql("""
            SELECT l.link_id,l.current_revision,s.summary::text FROM encounter_intervention_link l
            JOIN encounter_intervention_summary s ON s.practice_id=l.practice_id AND s.link_id=l.link_id AND s.revision=l.current_revision
            WHERE l.practice_id=:p AND l.patient_id=:patient AND l.encounter_id=:e ORDER BY l.created_at,l.link_id
            """).param("p",c.practiceId()).param("patient",patient).param("e",encounter)
            .query((r,n)->new Item(r.getObject(1,UUID.class),r.getLong(2),ClinicalJson.read(r.getString(3),Summary.class))).list();
    }
    public Page list(Context c, UUID patient, UUID encounter, int page, int size) {
        if(page<0||size<1||size>100) throw new ClinicalAccessException("INVALID_PAGE",422);
        backfillFollowups(c,patient,encounter);
        var allowed=capabilities(c,patient,encounter).stream().map(InterventionAuthorization.Capability::kind).collect(java.util.stream.Collectors.toSet());
        var items=all(c,patient,encounter).stream()
            .filter(x->allowed.contains(x.summary().kind().name()) && !"ABANDONED".equals(x.summary().clinicalState())).toList();
        journal.audit(c,encounter,"INTERVENTIONS_READ",null);
        return new Page(version(c,patient,encounter),items.stream().skip((long)page*size).limit(size).toList(),items.size(),page,size);
    }
    public Manifest capture(Context c, UUID patient, UUID encounter, UUID id) {
        lock(c,patient,encounter);
        backfillFollowups(c,patient,encounter);
        // Abandoned drafts remain in source and summary history, but are no longer active note plans.
        var items=all(c,patient,encounter).stream()
            .filter(item->!"ABANDONED".equals(item.summary().clinicalState())).toList();
        for(var item:items) require(c,item.summary().kind(),InterventionAuthorization.Action.READ);
        var version=version(c,patient,encounter); var checksum=ClinicalJson.hash(Map.of("version",version,"items",items));
        c.jdbc().sql("INSERT INTO encounter_intervention_manifest(practice_id,patient_id,encounter_id,manifest_id,set_version,items,checksum,created_by) VALUES(:p,:patient,:e,:id,:v,CAST(:items AS jsonb),:hash,:a)")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).param("id",id).param("v",version).param("items",ClinicalJson.write(items)).param("hash",checksum).param("a",c.actorId()).update();
        return new Manifest(id,version,items,checksum);
    }
    public Manifest manifest(Context c, UUID patient, UUID encounter, UUID id) {
        context(c,patient,encounter);
        var result=c.jdbc().sql("SELECT set_version,items::text,checksum FROM encounter_intervention_manifest WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:e AND manifest_id=:id")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).param("id",id)
            .query((r,n)->new Manifest(id,r.getLong(1),Arrays.asList(ClinicalJson.read(r.getString(2),Item[].class)),r.getString(3)))
            .optional().orElseThrow(()->new ClinicalAccessException("MANIFEST_UNAVAILABLE",404));
        for(var item:result.items()) require(c,item.summary().kind(),InterventionAuthorization.Action.READ);
        return result;
    }
    private void backfillFollowups(Context c,UUID patient,UUID encounter) {
        lock(c,patient,encounter);
        var missing=c.jdbc().sql("SELECT f.* FROM encounter_followup_intent f WHERE f.practice_id=:p AND f.patient_id=:patient AND f.encounter_id=:e AND NOT EXISTS(SELECT 1 FROM encounter_intervention_link l WHERE l.practice_id=f.practice_id AND l.kind='FOLLOW_UP' AND l.source_id=f.intent_id)")
            .param("p",c.practiceId()).param("patient",patient).param("e",encounter).query((r,n)->new Summary(Kind.FOLLOW_UP,r.getObject("intent_id",UUID.class),0,"Follow-up task",List.of(r.getString("instruction")),"REQUESTED","PENDING","NOT_STARTED","NOT_APPLICABLE",r.getObject("owner_id",UUID.class),r.getObject("due_date",java.time.LocalDate.class),r.getObject("received_at",java.time.OffsetDateTime.class),null,null)).list();
        for(var summary:missing) append(c,patient,encounter,summary);
    }
    private record Row(UUID site,UUID owner,UUID release,String care,String note) {}
    private record Link(UUID id,UUID patient,UUID encounter,long revision) {}
    private record Previous(long version,String hash) {}
}
