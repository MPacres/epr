package ph.epr.api.patientregistry;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ph.epr.api.platformoperations.TenantTransactions.Context;

public interface ClinicalPatientRegistry {
    /** Minimal identity for a server-authorized accepted work item; never grants chart access. No direct endpoint. */
    Patient identityForAcceptedWork(Context context, UUID patientId);
    Patient requirePatient(Context context, UUID patientId);
    /** Fixture-only guard, implemented by the registry that owns the synthetic marker. */
    Patient requireSyntheticPatient(Context context, UUID patientId);
    List<Patient> list(Context context);
    Patient register(Context context, UUID key, Registration request);
    boolean visitsCurrent(Context c);
    void replaceVisits(Context c, List<VisitSummary> visits);
    void visitsChanged(Context c);
    record VisitSummary(UUID patientId, UUID encounterId, long sourceVersion, java.time.OffsetDateTime serviceTime, String visitType) {}
    record Patient(UUID patientId, String recordNumber, String displayName, LocalDate birthDate, String identityStatus, long version, String birthAccuracy) {
        public Patient(UUID patientId, String recordNumber, String displayName, LocalDate birthDate, String identityStatus, long version) {
            this(patientId,recordNumber,displayName,birthDate,identityStatus,version,birthDate==null?"unknown":"exact");
        }
    }
    record Registration(UUID patientId, String displayName, LocalDate birthDate, String identityStatus,
                        boolean syntheticOnly, boolean duplicateReviewConfirmed) {}
}
