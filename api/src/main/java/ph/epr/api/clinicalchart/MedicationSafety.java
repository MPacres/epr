package ph.epr.api.clinicalchart;
import java.time.*;
import java.util.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
public interface MedicationSafety {
 record SafetyReview(UUID reviewId,String allergyState,List<String> allergies,List<String> currentMedications,String source,UUID reviewedBy,OffsetDateTime reviewedAt,boolean interactionsUnavailableAcknowledged) {}
 record ReviewMedicationSafety(UUID reviewId,String allergyState,List<String> allergies,List<String> currentMedications,String source,boolean interactionsUnavailableAcknowledged) {}
 SafetyReview record(Context c,UUID patient,UUID key,ReviewMedicationSafety request);
 void requireCurrent(Context c,UUID patient,UUID reviewId);
 SafetyReview require(Context c,UUID patient,UUID reviewId);
}
