package ph.epr.api.patientregistry.infrastructure;

import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.TenantCommandJournal;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
class JdbcClinicalPatientRegistry implements ClinicalPatientRegistry {
    private final TenantCommandJournal journal;
    JdbcClinicalPatientRegistry(TenantCommandJournal journal) { this.journal = journal; }
    private static final String QUERY = """
        SELECT p.patient_id, p.record_number, p.display_name, p.birth_date, p.identity_status, p.version, p.birth_accuracy
        FROM clinical_patient p JOIN patient_care_assignment a USING(practice_id, patient_id)
        WHERE p.practice_id = :practice AND a.clinician_id = :actor AND a.enabled
        """;

    public Patient identityForAcceptedWork(Context context,UUID patientId) {
        journal.audit(context,patientId,"ACCEPTED_WORK_IDENTITY_READ",null);
        return context.jdbc().sql("SELECT patient_id,record_number,display_name,birth_date,identity_status,version,birth_accuracy FROM clinical_patient WHERE practice_id=:p AND patient_id=:id")
            .param("p",context.practiceId()).param("id",patientId).query((rs,n)->patient(rs)).optional().orElseThrow(()->new ClinicalAccessException("PATIENT_UNAVAILABLE",404));
    }
    public Patient requirePatient(Context context, UUID patientId) {
        return context.jdbc().sql(QUERY + " AND p.patient_id = :patient")
            .param("practice", context.practiceId()).param("actor", context.actorId()).param("patient", patientId)
            .query((rs, n) -> patient(rs)).optional().orElseThrow(() -> new ClinicalAccessException("PATIENT_UNAVAILABLE", 404));
    }

    public Patient requireSyntheticPatient(Context context, UUID patientId) {
        var patient = requirePatient(context, patientId);
        var synthetic = context.jdbc().sql("SELECT synthetic_only FROM clinical_patient WHERE practice_id = :practice AND patient_id = :patient")
            .param("practice", context.practiceId()).param("patient", patientId)
            .query(Boolean.class).single();
        if (!synthetic) throw new ClinicalAccessException("SYNTHETIC_PATIENT_REQUIRED", 422);
        return patient;
    }

    public List<Patient> list(Context context) {
        var result = context.jdbc().sql(QUERY + " ORDER BY p.received_at DESC LIMIT 100")
            .param("practice", context.practiceId()).param("actor", context.actorId()).query((rs, n) -> patient(rs)).list();
        journal.audit(context, context.actorId(), "PATIENT_DIRECTORY_READ", null);
        return result;
    }

    public Patient register(Context context, UUID key, Registration request) {
        if (!request.syntheticOnly() || request.patientId() == null || request.displayName() == null
            || request.displayName().isBlank() || request.displayName().length() > 200
            || !Set.of("PROVISIONAL", "REPORTED").contains(request.identityStatus()))
            throw new ClinicalAccessException("INVALID_SYNTHETIC_PATIENT", 422);
        if (context.jdbc().sql("SELECT EXISTS(SELECT 1 FROM clinical_patient WHERE practice_id = :practice AND patient_id = :patient)")
            .param("practice", context.practiceId()).param("patient", request.patientId()).query(Boolean.class).single())
            requirePatient(context, request.patientId());
        // Serialize candidate checks with registration to avoid two blind concurrent inserts.
        context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))")
            .param("scope", context.practiceId() + "/patient-registration").query((rs, n) -> true).single();
        return journal.execute(context, "register-patient", key, request, Patient.class, () -> {
            var duplicate = context.jdbc().sql("""
                SELECT EXISTS(SELECT 1 FROM clinical_patient p JOIN patient_care_assignment a USING(practice_id, patient_id)
                    WHERE p.practice_id = :practice AND a.clinician_id = :actor AND a.enabled
                    AND lower(trim(display_name)) = lower(trim(:name)) AND birth_date IS NOT DISTINCT FROM :birth)
                """).param("practice", context.practiceId()).param("name", request.displayName())
                .param("birth", request.birthDate(), java.sql.Types.DATE).param("actor", context.actorId()).query(Boolean.class).single();
            if (duplicate) throw new ClinicalAccessException("DUPLICATE_REVIEW_REQUIRED", 409);
            context.jdbc().sql("""
                INSERT INTO clinical_patient(practice_id, patient_id, display_name, birth_date, identity_status, created_by, birth_accuracy, original_display_name)
                VALUES (:practice, :patient, :name, :birth, :status, :actor, CASE WHEN CAST(:birth AS date) IS NULL THEN 'unknown' ELSE 'exact' END, :name)
                """).param("practice", context.practiceId()).param("patient", request.patientId()).param("name", request.displayName().strip())
                .param("birth", request.birthDate(), java.sql.Types.DATE).param("status", request.identityStatus()).param("actor", context.actorId()).update();
            context.jdbc().sql("""
                INSERT INTO patient_care_assignment(practice_id, patient_id, clinician_id) VALUES (:practice, :patient, :actor)
                """).param("practice", context.practiceId()).param("patient", request.patientId()).param("actor", context.actorId()).update();
            journal.audit(context, request.patientId(), "SYNTHETIC_PATIENT_REGISTERED", duplicate ? "Separate record confirmed after duplicate review" : null);
            return requirePatient(context, request.patientId());
        });
    }
    private void lockVisits(Context c) {
        c.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
            .param("key",c.practiceId()+"/visit-summary/"+c.siteId()+"/"+c.actorId()).query((rs,n)->true).single();
    }
    public boolean visitsCurrent(Context c) {
        lockVisits(c);
        return c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM patient_visit_projection_state WHERE practice_id=:practice AND site_id=:site AND clinician_id=:actor AND reproductive_access=:reproductive AND refreshed_at > CURRENT_TIMESTAMP - INTERVAL '5 minutes')")
            .param("practice",c.practiceId()).param("site",c.siteId()).param("actor",c.actorId()).param("reproductive",c.reproductiveAccess()).query(Boolean.class).single();
    }
    public void replaceVisits(Context c, List<VisitSummary> visits) {
        lockVisits(c);
        // Called by the owning encounter service under its clinician lock, after authorized reconciliation.
        c.jdbc().sql("DELETE FROM patient_visit_projection WHERE practice_id=:practice AND site_id=:site AND clinician_id=:actor")
            .param("practice",c.practiceId()).param("site",c.siteId()).param("actor",c.actorId()).update();
        for(var visit:visits) c.jdbc().sql("""
            INSERT INTO patient_visit_projection(practice_id,site_id,clinician_id,patient_id,encounter_id,source_version,service_time,visit_type)
            VALUES (:practice,:site,:actor,:patient,:encounter,:version,:time,:type)
            ON CONFLICT(practice_id,site_id,clinician_id,encounter_id) DO NOTHING
            """).param("practice",c.practiceId()).param("site",c.siteId()).param("actor",c.actorId()).param("patient",visit.patientId())
            .param("encounter",visit.encounterId()).param("version",visit.sourceVersion()).param("time",visit.serviceTime()).param("type",visit.visitType()).update();
        c.jdbc().sql("""
            INSERT INTO patient_visit_projection_state(practice_id,site_id,clinician_id,reproductive_access)
            VALUES (:practice,:site,:actor,:reproductive)
            ON CONFLICT(practice_id,site_id,clinician_id) DO UPDATE SET reproductive_access=EXCLUDED.reproductive_access, refreshed_at=CURRENT_TIMESTAMP
            """).param("practice",c.practiceId()).param("site",c.siteId()).param("actor",c.actorId()).param("reproductive",c.reproductiveAccess()).update();
    }
    public void visitsChanged(Context c) {
        lockVisits(c);
        c.jdbc().sql("DELETE FROM patient_visit_projection_state WHERE practice_id=:practice AND site_id=:site AND clinician_id=:actor")
            .param("practice",c.practiceId()).param("site",c.siteId()).param("actor",c.actorId()).update();
    }
    private Patient patient(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Patient(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
            rs.getObject(4, java.time.LocalDate.class), rs.getString(5), rs.getLong(6), rs.getString(7));
    }
}
