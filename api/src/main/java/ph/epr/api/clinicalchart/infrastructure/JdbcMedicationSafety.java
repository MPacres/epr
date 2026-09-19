package ph.epr.api.clinicalchart.infrastructure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.clinicalchart.MedicationSafety;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Service
public class JdbcMedicationSafety implements MedicationSafety {
 private final ClinicalPatientRegistry patients;private final InterventionAuthorization authorization;private final TenantCommandJournal journal;
 public JdbcMedicationSafety(ClinicalPatientRegistry patients,InterventionAuthorization authorization,TenantCommandJournal journal){this.patients=patients;this.authorization=authorization;this.journal=journal;}
 private void access(Context c,UUID patient){patients.requirePatient(c,patient);authorization.require(c.actorId(),c.practiceId(),c.siteId(),"PRESCRIPTION",InterventionAuthorization.Action.READ);}
 public SafetyReview record(Context c,UUID patient,UUID key,ReviewMedicationSafety request){
  access(c,patient);authorization.require(c.actorId(),c.practiceId(),c.siteId(),"PRESCRIPTION",InterventionAuthorization.Action.AUTHORIZE);
  if(request.reviewId()==null||request.allergyState()==null||!Set.of("UNKNOWN","KNOWN","NONE_KNOWN").contains(request.allergyState())||request.source()==null||request.source().isBlank()||request.source().length()>2000||request.allergies()==null||request.currentMedications()==null)throw new ClinicalAccessException("SAFETY_REVIEW_REQUIRED",422);
  if(request.allergies().size()>100||request.currentMedications().size()>100||java.util.stream.Stream.concat(request.allergies().stream(),request.currentMedications().stream()).anyMatch(s->s==null||s.isBlank()||s.length()>200))throw new ClinicalAccessException("INVALID_SAFETY_INGREDIENT",422);
  if(request.allergyState().equals("KNOWN")!=!request.allergies().isEmpty())throw new ClinicalAccessException("ALLERGY_STATE_CONFLICT",422);
  return journal.execute(c,"medication-safety/"+patient,key,request,SafetyReview.class,()->{
   c.jdbc().sql("INSERT INTO medication_safety_review(practice_id,patient_id,review_id,site_id,allergy_state,source,reviewed_by,interactions_unavailable_acknowledged) VALUES(:p,:patient,:id,:site,:state,:source,:a,:ack)")
    .param("p",c.practiceId()).param("patient",patient).param("id",request.reviewId()).param("site",c.siteId()).param("state",request.allergyState()).param("source",request.source()).param("a",c.actorId()).param("ack",request.interactionsUnavailableAcknowledged()).update();
   for(var kind:List.of("ALLERGY","MEDICATION"))for(var ingredient:kind.equals("ALLERGY")?request.allergies():request.currentMedications())c.jdbc().sql("INSERT INTO medication_safety_ingredient(practice_id,review_id,kind,ingredient) VALUES(:p,:id,:kind,:ingredient) ON CONFLICT DO NOTHING")
    .param("p",c.practiceId()).param("id",request.reviewId()).param("kind",kind).param("ingredient",ingredient.trim().toLowerCase(Locale.ROOT)).update();
   journal.audit(c,request.reviewId(),"SYNTHETIC_MEDICATION_SAFETY_REVIEWED",null);return require(c,patient,request.reviewId());
  });
 }
 public SafetyReview require(Context c,UUID patient,UUID id){
  access(c,patient);
  return c.jdbc().sql("SELECT * FROM medication_safety_review WHERE practice_id=:p AND patient_id=:patient AND review_id=:id AND site_id=:site")
   .param("p",c.practiceId()).param("patient",patient).param("id",id).param("site",c.siteId())
   .query((r,n)->new SafetyReview(id,r.getString("allergy_state"),ingredients(c,id,"ALLERGY"),ingredients(c,id,"MEDICATION"),r.getString("source"),r.getObject("reviewed_by",UUID.class),r.getObject("reviewed_at",OffsetDateTime.class),r.getBoolean("interactions_unavailable_acknowledged")))
   .optional().orElseThrow(()->new ClinicalAccessException("MEDICATION_SAFETY_REVIEW_UNAVAILABLE",422));
 }
 private List<String> ingredients(Context c,UUID id,String kind){return c.jdbc().sql("SELECT ingredient FROM medication_safety_ingredient WHERE practice_id=:p AND review_id=:id AND kind=:kind ORDER BY ingredient").param("p",c.practiceId()).param("id",id).param("kind",kind).query(String.class).list();}
 public void requireCurrent(Context c,UUID patient,UUID id){
  access(c,patient);
  var current=c.jdbc().sql("SELECT review_id FROM medication_safety_review WHERE practice_id=:p AND patient_id=:patient ORDER BY review_sequence DESC LIMIT 1").param("p",c.practiceId()).param("patient",patient).query(UUID.class).optional();
  if(current.isEmpty()||!current.get().equals(id))throw new ClinicalAccessException("SAFETY_REVIEW_SUPERSEDED",422);
 }
}
