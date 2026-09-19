package ph.epr.api.carecoordination.infrastructure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterContinuity;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Service
public class JdbcClinicalWorklist {
    private final EncounterContinuity source;
    private final TenantCommandJournal journal;
    private final ph.epr.api.identitytenancy.InterventionAuthorization authorization;
    private final ph.epr.api.carecoordination.application.InterventionWorkService interventionWork;
    private final ph.epr.api.encountertemplates.EncounterInterventionLinks links;
    public JdbcClinicalWorklist(EncounterContinuity source, TenantCommandJournal journal, ph.epr.api.encountertemplates.EncounterInterventionLinks links, ph.epr.api.identitytenancy.InterventionAuthorization authorization, ph.epr.api.carecoordination.application.InterventionWorkService interventionWork) { this.source = source; this.journal = journal; this.links=links;this.authorization=authorization;this.interventionWork=interventionWork; }
    public record Task(UUID sourceId, UUID patientId, String patientName, String recordNumber, UUID encounterId, UUID ownerId, String kind, String instruction, LocalDate dueDate, String state, long version, OffsetDateTime acknowledgedAt,boolean currentOwner) {
        public Task(UUID sourceId,UUID patientId,String patientName,String recordNumber,UUID encounterId,UUID ownerId,String kind,String instruction,LocalDate dueDate,String state,long version,OffsetDateTime acknowledgedAt) {this(sourceId,patientId,patientName,recordNumber,encounterId,ownerId,kind,instruction,dueDate,state,version,acknowledgedAt,true);}
    }
    public record Resolve(long expectedVersion, String reason) {}
    /** Demand-driven durable consumer: projection and delivery acknowledgment commit together. Safe to replay. */
    public List<Task> reconcile(Context c) {
        var obligations = source.obligations(c);
        for (var item : obligations) c.jdbc().sql("""
            INSERT INTO clinical_owned_task(practice_id, source_id, source_version, patient_id, encounter_id, owner_id, kind, instruction, due_date, state)
            VALUES (:practice, :source, :version, :patient, :encounter, :owner, :kind, :instruction, :due, :state)
            ON CONFLICT (practice_id, source_id) DO UPDATE SET source_version = EXCLUDED.source_version,
                state = CASE WHEN clinical_owned_task.kind = 'UNSIGNED_NOTE' THEN EXCLUDED.state ELSE clinical_owned_task.state END,
                version = clinical_owned_task.version + 1, acknowledged_at = CURRENT_TIMESTAMP
            WHERE clinical_owned_task.source_version < EXCLUDED.source_version
            """).param("practice", c.practiceId()).param("source", item.sourceId()).param("version", item.sourceVersion())
            .param("patient", item.patientId()).param("encounter", item.encounterId()).param("owner", item.ownerId()).param("kind", item.kind())
            .param("instruction", item.instruction()).param("due", item.dueDate(), java.sql.Types.DATE).param("state", item.open() ? "OPEN" : "RESOLVED").update();
        journal.acknowledgeEvents(c, obligations.stream().map(EncounterContinuity.Obligation::sourceId).toList());
        return combined(c,obligations);
    }
    public List<Task> list(Context c) {
        var tasks = combined(c, source.obligations(c));
        journal.audit(c, c.actorId(), "OWNED_WORKLIST_READ", null);
        return tasks;
    }
    private List<Task> authorizedTasks(Context c, List<EncounterContinuity.Obligation> obligations) {
        var byId = obligations.stream().collect(java.util.stream.Collectors.toMap(EncounterContinuity.Obligation::sourceId, java.util.function.Function.identity()));
        var ids = obligations.stream().map(EncounterContinuity.Obligation::sourceId).collect(java.util.stream.Collectors.toSet());
        return c.jdbc().sql("SELECT * FROM clinical_owned_task WHERE practice_id = :practice AND owner_id = :owner ORDER BY due_date NULLS LAST, acknowledged_at")
            .param("practice", c.practiceId()).param("owner", c.actorId()).query((rs,n) -> new Task(rs.getObject("source_id", UUID.class), rs.getObject("patient_id", UUID.class),
                byId.containsKey(rs.getObject("source_id", UUID.class)) ? byId.get(rs.getObject("source_id", UUID.class)).patientName() : "",
                byId.containsKey(rs.getObject("source_id", UUID.class)) ? byId.get(rs.getObject("source_id", UUID.class)).recordNumber() : "",
                rs.getObject("encounter_id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("kind"), rs.getString("instruction"), rs.getObject("due_date", LocalDate.class),
                rs.getString("state"), rs.getLong("version"), rs.getObject("acknowledged_at", OffsetDateTime.class))).list().stream().filter(t -> ids.contains(t.sourceId())).toList();
    }
    public Task resolve(Context c, UUID id, UUID key, Resolve request) {
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) throw new ClinicalAccessException("DISPOSITION_REQUIRED", 422);
        if (list(c).stream().noneMatch(t -> t.sourceId().equals(id))) throw new ClinicalAccessException("TASK_UNAVAILABLE", 404);
        var task=list(c).stream().filter(t->t.sourceId().equals(id)).findFirst().orElseThrow();
        links.require(c,ph.epr.api.encountertemplates.EncounterInterventionLinks.Kind.FOLLOW_UP,ph.epr.api.identitytenancy.InterventionAuthorization.Action.CONTINUE_EXISTING);
        return journal.execute(c, "resolve-follow-up/" + id, key, request, Task.class, () -> {
            links.lockAcceptedWork(c,task.patientId(),task.encounterId());
            int updated = c.jdbc().sql("UPDATE clinical_owned_task SET state = 'RESOLVED', version = version + 1 WHERE practice_id = :practice AND source_id = :id AND owner_id = :owner AND kind = 'FOLLOW_UP' AND state = 'OPEN' AND version = :version")
                .param("practice", c.practiceId()).param("id", id).param("owner", c.actorId()).param("version", request.expectedVersion()).update();
            if (updated != 1) throw new ClinicalAccessException("TASK_VERSION_OR_STATE_CONFLICT", 409);
            links.append(c,task.patientId(),task.encounterId(),new ph.epr.api.encountertemplates.EncounterInterventionLinks.Summary(ph.epr.api.encountertemplates.EncounterInterventionLinks.Kind.FOLLOW_UP,id,task.version()+1,"Follow-up task",List.of(task.instruction(),request.reason()),"RESOLVED","ACKNOWLEDGED","COMPLETED","REVIEWED",task.ownerId(),task.dueDate(),OffsetDateTime.now(),null,null));
            journal.audit(c, id, "FOLLOW_UP_RESOLVED", request.reason());
            return list(c).stream().filter(t -> t.sourceId().equals(id)).findFirst().orElseThrow();
        });
    }

    private List<Task> combined(Context c,List<EncounterContinuity.Obligation> obligations) {
        var canReadFollowUp=authorization.capabilities(c.actorId(),c.practiceId(),c.siteId()).stream().anyMatch(x->x.kind().equals("FOLLOW_UP")&&x.read());
        var result=new ArrayList<>(authorizedTasks(c,obligations.stream().filter(x->!x.kind().equals("FOLLOW_UP")||canReadFollowUp).toList()));
        var ids=result.stream().map(Task::sourceId).collect(java.util.stream.Collectors.toSet());
        var assigned=c.jdbc().sql("SELECT * FROM clinical_owned_task WHERE practice_id=:p AND kind='FOLLOW_UP' AND (owner_id=:a AND accepted_transfer OR proposed_owner_id=:a)")
            .param("p",c.practiceId()).param("a",c.actorId()).query((r,n)->new Task(r.getObject("source_id",UUID.class),r.getObject("patient_id",UUID.class),"","",r.getObject("encounter_id",UUID.class),r.getObject("owner_id",UUID.class),"FOLLOW_UP",r.getString("instruction"),r.getObject("due_date",LocalDate.class),r.getString("state"),r.getLong("version"),r.getObject("acknowledged_at",OffsetDateTime.class))).list();
        for(var task:assigned)if(!ids.contains(task.sourceId())) {
            authorization.require(c.actorId(),c.practiceId(),c.siteId(),"FOLLOW_UP",ph.epr.api.identitytenancy.InterventionAuthorization.Action.READ);
            var context=links.acceptedWorkContext(c,task.patientId(),task.encounterId());
            result.add(new Task(task.sourceId(),task.patientId(),context.patientName(),context.recordNumber(),task.encounterId(),task.ownerId(),task.kind(),task.instruction(),task.dueDate(),task.state(),task.version(),task.acknowledgedAt(),task.ownerId().equals(c.actorId())));
        }
        for(var work:interventionWork.list(c)) {
            var context=links.acceptedWorkContext(c,work.patientId(),work.encounterId());
            result.add(new Task(work.sourceId(),work.patientId(),context.patientName(),context.recordNumber(),work.encounterId(),work.ownerId(),work.kind(),work.instruction(),work.dueDate(),work.state(),work.version(),null,work.ownerId().equals(c.actorId())));
        }
        return List.copyOf(result);
    }
    public record TransferFollowUp(long expectedVersion,UUID ownerId,String reason) {}
    public record AcceptFollowUp(long expectedVersion,String reason) {}
    public Task transfer(Context c,UUID id,UUID key,TransferFollowUp request) {
        var task=list(c).stream().filter(t->t.sourceId().equals(id)&&t.kind().equals("FOLLOW_UP")&&t.ownerId().equals(c.actorId())).findFirst().orElseThrow(()->new ClinicalAccessException("TASK_UNAVAILABLE",404));
        authorization.require(c.actorId(),c.practiceId(),c.siteId(),"FOLLOW_UP",ph.epr.api.identitytenancy.InterventionAuthorization.Action.CONTINUE_EXISTING);
        if(request.ownerId()==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>500)throw new ClinicalAccessException("HANDOFF_DETAILS_REQUIRED",422);
        authorization.require(request.ownerId(),c.practiceId(),c.siteId(),"FOLLOW_UP",ph.epr.api.identitytenancy.InterventionAuthorization.Action.CONTINUE_EXISTING);
        return journal.execute(c,"propose-follow-up-handoff/"+id,key,request,Task.class,()->{
            links.lockAcceptedWork(c,task.patientId(),task.encounterId());
            var updated=c.jdbc().sql("UPDATE clinical_owned_task SET proposed_owner_id=:next,version=version+1 WHERE practice_id=:p AND source_id=:id AND owner_id=:a AND version=:v AND state='OPEN' AND kind='FOLLOW_UP'")
                .param("next",request.ownerId()).param("p",c.practiceId()).param("id",id).param("a",c.actorId()).param("v",request.expectedVersion()).update();
            if(updated!=1)throw new ClinicalAccessException("TASK_VERSION_OR_STATE_CONFLICT",409);
            journal.audit(c,id,"FOLLOW_UP_HANDOFF_PROPOSED",request.reason());return list(c).stream().filter(t->t.sourceId().equals(id)).findFirst().orElseThrow();
        });
    }
    public Task accept(Context c,UUID id,UUID key,AcceptFollowUp request) {
        var task=list(c).stream().filter(t->t.sourceId().equals(id)&&t.kind().equals("FOLLOW_UP")).findFirst().orElseThrow(()->new ClinicalAccessException("TASK_UNAVAILABLE",404));
        authorization.require(c.actorId(),c.practiceId(),c.siteId(),"FOLLOW_UP",ph.epr.api.identitytenancy.InterventionAuthorization.Action.CONTINUE_EXISTING);
        if(request.reason()==null||request.reason().isBlank()||request.reason().length()>500)throw new ClinicalAccessException("HANDOFF_DETAILS_REQUIRED",422);
        return journal.execute(c,"accept-follow-up-handoff/"+id,key,request,Task.class,()->{
            links.lockAcceptedWork(c,task.patientId(),task.encounterId());
            var updated=c.jdbc().sql("UPDATE clinical_owned_task SET owner_id=:a,proposed_owner_id=NULL,accepted_transfer=true,version=version+1 WHERE practice_id=:p AND source_id=:id AND proposed_owner_id=:a AND version=:v AND state='OPEN' AND kind='FOLLOW_UP'")
                .param("p",c.practiceId()).param("id",id).param("a",c.actorId()).param("v",request.expectedVersion()).update();
            if(updated!=1)throw new ClinicalAccessException("TASK_VERSION_OR_STATE_CONFLICT",409);
            journal.audit(c,id,"FOLLOW_UP_HANDOFF_ACCEPTED",request.reason());return list(c).stream().filter(t->t.sourceId().equals(id)).findFirst().orElseThrow();
        });
    }
}
