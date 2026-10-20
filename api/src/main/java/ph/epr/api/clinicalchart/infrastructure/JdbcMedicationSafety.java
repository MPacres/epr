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
 private final ClinicalPatientRegistry patients;private final InterventionAuthorization authorization;
 public JdbcMedicationSafety(ClinicalPatientRegistry patients,InterventionAuthorization authorization){this.patients=patients;this.authorization=authorization;}
 private void access(Context c,UUID patient){patients.requirePatient(c,patient);authorization.require(c.actorId(),c.practiceId(),c.siteId(),"PRESCRIPTION",InterventionAuthorization.Action.READ);}
 public SafetyReview record(Context c,UUID patient,UUID key,ReviewMedicationSafety request){
  access(c,patient);throw new ClinicalAccessException("LEGACY_SAFETY_REVIEW_DISABLED",410);
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
