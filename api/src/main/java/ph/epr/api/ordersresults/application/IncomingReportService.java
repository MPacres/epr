package ph.epr.api.ordersresults.application;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterInterventionLinks;
import ph.epr.api.encountertemplates.EncounterInterventionLinks.Kind;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Service
public class IncomingReportService {
 private final ServiceRequestService requests;private final EncounterInterventionLinks links;private final TenantCommandJournal journal;
 public IncomingReportService(ServiceRequestService requests,EncounterInterventionLinks links,TenantCommandJournal journal){this.requests=requests;this.links=links;this.journal=journal;}
 public record IncomingReport(UUID id,long version,String kind,String source,String externalReference,String content,OffsetDateTime occurredAt,OffsetDateTime receivedAt,UUID correctsId,UUID patientId,UUID requestId,String state) {}
 public record ReceiveReport(UUID id,String kind,String source,String externalReference,String content,OffsetDateTime occurredAt,UUID correctsId) {}
 public record MatchReport(long expectedVersion,UUID patientId,UUID requestId,long expectedRequestVersion,String reason,boolean confirmPatientAndSource) {}
 public IncomingReport receive(Context c,UUID key,ReceiveReport r){
  if(r.kind()==null||!Set.of("LAB_ORDER","IMAGING_ORDER").contains(r.kind()))fail("REPORT_KIND_REQUIRED");
  links.require(c,Kind.valueOf(r.kind()),InterventionAuthorization.Action.CONTINUE_EXISTING);
  if(r.id()==null||r.occurredAt()==null||invalid(r.source(),1000)||invalid(r.externalReference(),300)||invalid(r.content(),10000))fail("REPORT_SOURCE_CONTENT_REQUIRED");
  if(r.correctsId()!=null)get(c,r.correctsId());
  return journal.execute(c,"receive-report",key,r,IncomingReport.class,()->{
   c.jdbc().sql("INSERT INTO incoming_service_report(practice_id,site_id,id,kind,source,external_reference,content,occurred_at,received_by,corrects_id) VALUES(:p,:site,:id,:kind,:source,:reference,:content,:occurred,:a,:corrects)")
    .param("p",c.practiceId()).param("site",c.siteId()).param("id",r.id()).param("kind",r.kind()).param("source",r.source()).param("reference",r.externalReference()).param("content",r.content()).param("occurred",r.occurredAt()).param("a",c.actorId()).param("corrects",r.correctsId(),java.sql.Types.OTHER).update();
   journal.audit(c,r.id(),"EXTERNAL_REPORT_QUARANTINED",null);return get(c,r.id());
  });
 }
 public IncomingReport get(Context c,UUID id){
  var result=c.jdbc().sql("SELECT * FROM incoming_service_report WHERE practice_id=:p AND site_id=:s AND id=:id")
   .param("p",c.practiceId()).param("s",c.siteId()).param("id",id).query((r,n)->new IncomingReport(id,r.getLong("version"),r.getString("kind"),r.getString("source"),r.getString("external_reference"),r.getString("content"),r.getObject("occurred_at",OffsetDateTime.class),r.getObject("received_at",OffsetDateTime.class),r.getObject("corrects_id",UUID.class),r.getObject("patient_id",UUID.class),r.getObject("request_id",UUID.class),r.getString("state"))).optional().orElseThrow(()->new ClinicalAccessException("REPORT_UNAVAILABLE",404));
  links.require(c,Kind.valueOf(result.kind()),InterventionAuthorization.Action.READ);
  if(result.state().equals("UNMATCHED")&&!c.jdbc().sql("SELECT received_by=:a FROM incoming_service_report WHERE practice_id=:p AND id=:id").param("a",c.actorId()).param("p",c.practiceId()).param("id",id).query(Boolean.class).single())throw new ClinicalAccessException("REPORT_UNAVAILABLE",404);
  if(result.requestId()!=null)requests.get(c,result.patientId(),result.requestId());
  return result;
 }
 public List<IncomingReport> unmatched(Context c){
  return c.jdbc().sql("SELECT id FROM incoming_service_report WHERE practice_id=:p AND site_id=:s AND received_by=:a AND state='UNMATCHED' ORDER BY received_at LIMIT 100")
   .param("p",c.practiceId()).param("s",c.siteId()).param("a",c.actorId()).query(UUID.class).list().stream().map(id->get(c,id)).toList();
 }
 public List<IncomingReport> forRequest(Context c,UUID patient,UUID request){
  requests.get(c,patient,request);
  return c.jdbc().sql("SELECT id FROM incoming_service_report WHERE practice_id=:p AND patient_id=:patient AND request_id=:request ORDER BY received_at").param("p",c.practiceId()).param("patient",patient).param("request",request).query(UUID.class).list().stream().map(id->get(c,id)).toList();
 }
 public IncomingReport match(Context c,UUID id,UUID key,MatchReport r){
  var report=get(c,id);links.require(c,Kind.valueOf(report.kind()),InterventionAuthorization.Action.CONTINUE_EXISTING);
  var source=requests.get(c,r.patientId(),r.requestId());
  if(!r.confirmPatientAndSource()||invalid(r.reason(),2000)||!source.data().kind().equals(report.kind()))fail("EXPLICIT_PATIENT_SOURCE_MATCH_REQUIRED");
  if(report.correctsId()!=null){var original=get(c,report.correctsId());if(!Objects.equals(original.requestId(),r.requestId())||!Objects.equals(original.patientId(),r.patientId()))fail("CORRECTION_SOURCE_MISMATCH");}
  return journal.execute(c,"match-report/"+id,key,r,IncomingReport.class,()->{
   requests.lockForReport(c,source.patientId(),source.id());
   var locked=c.jdbc().sql("SELECT version FROM incoming_service_report WHERE practice_id=:p AND id=:id FOR UPDATE").param("p",c.practiceId()).param("id",id).query(Long.class).single();
   if(locked!=r.expectedVersion()||!get(c,id).state().equals("UNMATCHED"))throw new ClinicalAccessException("REPORT_MATCH_CONFLICT",409);
   requests.result(c,r.patientId(),r.requestId(),id,new ServiceRequestService.ServiceRequestEvidence(r.expectedRequestVersion(),report.correctsId()==null?"RECEIVED":"CORRECTED","Verified report "+id+" · "+r.reason(),report.occurredAt()));
   c.jdbc().sql("UPDATE incoming_service_report SET state='MATCHED',patient_id=:patient,request_id=:request,version=version+1 WHERE practice_id=:p AND id=:id")
    .param("patient",r.patientId()).param("request",r.requestId()).param("p",c.practiceId()).param("id",id).update();
   c.jdbc().sql("INSERT INTO service_report_match_event(practice_id,report_id,patient_id,request_id,reason,matched_by) VALUES(:p,:id,:patient,:request,:reason,:a)")
    .param("p",c.practiceId()).param("id",id).param("patient",r.patientId()).param("request",r.requestId()).param("reason",r.reason()).param("a",c.actorId()).update();
   journal.audit(c,id,"REPORT_MATCHED_AFTER_HUMAN_REVIEW",null);return get(c,id);
  });
 }
 private boolean invalid(String s,int max){return s==null||s.isBlank()||s.length()>max;}
 private void fail(String code){throw new ClinicalAccessException(code,422);}
}
