package ph.epr.api.carecoordination.application;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.carecoordination.OwnedClinicalWork;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Service
public class InterventionWorkService implements OwnedClinicalWork {
 private final InterventionAuthorization authorization;private final TenantCommandJournal journal;
 public InterventionWorkService(InterventionAuthorization authorization,TenantCommandJournal journal){this.authorization=authorization;this.journal=journal;}
 public record InterventionWork(UUID sourceId,String kind,UUID patientId,UUID encounterId,UUID siteId,UUID ownerId,UUID proposedOwnerId,long version,long sourceVersion,String instruction,LocalDate dueDate,String state) {}
 public record TransferWork(long expectedVersion,UUID ownerId,String reason) {}
 public record AcceptWork(long expectedVersion,String reason) {}
 public void requireAcceptedAssignment(Context c,String kind,UUID id,UUID patient,UUID encounter){
  authorization.require(c.actorId(),c.practiceId(),c.siteId(),kind,InterventionAuthorization.Action.READ);
  boolean accepted=c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM intervention_owned_work WHERE practice_id=:p AND site_id=:s AND kind=:k AND source_id=:id AND patient_id=:patient AND encounter_id=:e AND owner_id=:a)")
   .param("p",c.practiceId()).param("s",c.siteId()).param("k",kind).param("id",id).param("patient",patient).param("e",encounter).param("a",c.actorId()).query(Boolean.class).single();
  if(!accepted)throw new ClinicalAccessException("ACCEPTED_WORK_ASSIGNMENT_REQUIRED",403);
 }
 public void register(Context c,Obligation o){
  if(!c.siteId().equals(o.siteId())||o.ownerId()==null)throw new ClinicalAccessException("WORK_CONTEXT_MISMATCH",422);
  c.jdbc().sql("""
   INSERT INTO intervention_owned_work(practice_id,source_id,kind,patient_id,encounter_id,site_id,owner_id,source_version,instruction,due_date)
   VALUES(:p,:id,:kind,:patient,:encounter,:site,:owner,:v,:instruction,:due)
   ON CONFLICT(practice_id,kind,source_id) DO UPDATE SET source_version=EXCLUDED.source_version,
    instruction=EXCLUDED.instruction,due_date=EXCLUDED.due_date,state='OPEN',version=intervention_owned_work.version+1
   WHERE intervention_owned_work.source_version<EXCLUDED.source_version
   """).param("p",c.practiceId()).param("id",o.sourceId()).param("kind",o.kind()).param("patient",o.patientId()).param("encounter",o.encounterId()).param("site",o.siteId()).param("owner",o.ownerId()).param("v",o.sourceVersion()).param("instruction",o.instruction()).param("due",o.dueDate(),java.sql.Types.DATE).update();
 }
 public void reviewed(Context c,String kind,UUID id,long sourceVersion,String evidence){
  c.jdbc().sql("UPDATE intervention_owned_work SET state='RESOLVED',version=version+1,source_version=:v WHERE practice_id=:p AND kind=:kind AND source_id=:id AND source_version<=:v")
   .param("p",c.practiceId()).param("kind",kind).param("id",id).param("v",sourceVersion).update();
  event(c,kind,id,"SOURCE_REVIEWED",evidence);
 }
 public List<InterventionWork> list(Context c){
  var kinds=authorization.capabilities(c.actorId(),c.practiceId(),c.siteId()).stream().filter(InterventionAuthorization.Capability::read).map(InterventionAuthorization.Capability::kind).toList();
  return c.jdbc().sql("SELECT * FROM intervention_owned_work WHERE practice_id=:p AND site_id=:site AND (owner_id=:a OR proposed_owner_id=:a) ORDER BY due_date NULLS LAST,received_at")
   .param("p",c.practiceId()).param("site",c.siteId()).param("a",c.actorId()).query((r,n)->new InterventionWork(r.getObject("source_id",UUID.class),r.getString("kind"),r.getObject("patient_id",UUID.class),r.getObject("encounter_id",UUID.class),r.getObject("site_id",UUID.class),r.getObject("owner_id",UUID.class),r.getObject("proposed_owner_id",UUID.class),r.getLong("version"),r.getLong("source_version"),r.getString("instruction"),r.getObject("due_date",LocalDate.class),r.getString("state"))).list().stream().filter(x->kinds.contains(x.kind())).toList();
 }
 public InterventionWork transfer(Context c,String kind,UUID id,UUID key,TransferWork request){
  var before=find(c,kind,id);authorization.require(c.actorId(),c.practiceId(),c.siteId(),kind,InterventionAuthorization.Action.CONTINUE_EXISTING);
  if(!before.ownerId().equals(c.actorId())||request.ownerId()==null)throw new ClinicalAccessException("WORK_OWNER_REQUIRED",403);
  authorization.require(request.ownerId(),c.practiceId(),c.siteId(),kind,InterventionAuthorization.Action.CONTINUE_EXISTING);reason(request.reason());
  return journal.execute(c,"transfer-work/"+kind+"/"+id,key,request,InterventionWork.class,()->{
   int changed=c.jdbc().sql("UPDATE intervention_owned_work SET proposed_owner_id=:owner,version=version+1 WHERE practice_id=:p AND kind=:kind AND source_id=:id AND owner_id=:a AND version=:v AND state='OPEN'")
    .param("owner",request.ownerId()).param("p",c.practiceId()).param("kind",kind).param("id",id).param("a",c.actorId()).param("v",request.expectedVersion()).update();
   if(changed!=1)throw new ClinicalAccessException("WORK_VERSION_CONFLICT",409);event(c,kind,id,"TRANSFER_PROPOSED",request.reason());return find(c,kind,id);
  });
 }
 public InterventionWork accept(Context c,String kind,UUID id,UUID key,AcceptWork request){
  find(c,kind,id);authorization.require(c.actorId(),c.practiceId(),c.siteId(),kind,InterventionAuthorization.Action.CONTINUE_EXISTING);reason(request.reason());
  return journal.execute(c,"accept-work/"+kind+"/"+id,key,request,InterventionWork.class,()->{
   int changed=c.jdbc().sql("UPDATE intervention_owned_work SET owner_id=:a,proposed_owner_id=NULL,version=version+1 WHERE practice_id=:p AND kind=:kind AND source_id=:id AND proposed_owner_id=:a AND version=:v AND state='OPEN'")
    .param("p",c.practiceId()).param("kind",kind).param("id",id).param("a",c.actorId()).param("v",request.expectedVersion()).update();
   if(changed!=1)throw new ClinicalAccessException("WORK_VERSION_CONFLICT",409);event(c,kind,id,"TRANSFER_ACCEPTED",request.reason());return find(c,kind,id);
  });
 }
 private InterventionWork find(Context c,String kind,UUID id){return list(c).stream().filter(x->x.sourceId().equals(id)&&x.kind().equals(kind)).findFirst().orElseThrow(()->new ClinicalAccessException("WORK_UNAVAILABLE",404));}
 private void reason(String s){if(s==null||s.isBlank()||s.length()>2000)throw new ClinicalAccessException("EVIDENCE_REQUIRED",422);}
 private void event(Context c,String kind,UUID id,String action,String evidence){
  c.jdbc().sql("INSERT INTO intervention_work_event(practice_id,event_id,source_id,kind,version,actor_id,action,evidence) SELECT practice_id,:event,source_id,kind,version,:a,:action,:evidence FROM intervention_owned_work WHERE practice_id=:p AND kind=:kind AND source_id=:id")
   .param("p",c.practiceId()).param("event",UUID.randomUUID()).param("id",id).param("kind",kind).param("a",c.actorId()).param("action",action).param("evidence",evidence).update();
  journal.audit(c,id,action,null);
 }
}
