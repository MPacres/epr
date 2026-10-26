package ph.epr.api.clinicalchart;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.SourceKind;
import ph.epr.api.platformoperations.TenantTransactions.Context;

/** Public application boundary for longitudinal allergies, medication use, problems, and exact reviews. */
public interface ClinicalLists {
    enum ListState { UNAVAILABLE, NOT_REVIEWED, EMPTY_REVIEWED, AVAILABLE, STALE }

    record Actions(boolean read, boolean propose, boolean write, boolean review) {}
    record Code(String system, String code, String version, String display) {}
    record Preview(UUID id, long version, String display, String status, Code code) {}
    record ListSummary(SourceKind kind, ListState state, long generation, long activeCount,
                       List<Preview> preview, ReviewManifest lastReview, Actions actions) {}
    record ClinicalSummary(ListSummary allergies, ListSummary medications, ListSummary problems) {}
    record Page<T>(List<T> items, int page, int pageSize, long total) {}

    record Reaction(UUID reactionId, String manifestation, String severity, String onset, String exposureRoute) {}
    record AllergyData(String substanceDisplay, Code code, String category, String reactionType,
                       String verificationStatus, String criticality, String onset, String onsetPrecision,
                       OffsetDateTime lastOccurrence, String informationSource, String asserter, String notes,
                       OffsetDateTime clinicalTime, UUID encounterId, List<Reaction> reactions) {}
    record AllergyRevision(long version, AllergyData data, String reason, UUID actorId, OffsetDateTime receivedAt) {}
    record Allergy(UUID id, UUID patientId, long version, String clinicalStatus, UUID createdBy,
                   OffsetDateTime createdAt, AllergyData data, List<AllergyRevision> revisions, boolean duplicateCandidate) {}
    record CreateAllergy(UUID id, AllergyData data) {}
    record ReviseAllergy(long expectedVersion, String reason, AllergyData data) {}

    record MedicationUseData(String medicationDisplay, Code code, String brand, String strength,
                             String formulation, BigDecimal dose, String doseUnit, String route,
                             String frequency, boolean prn, String effectiveStart, String effectiveEnd,
                             String effectivePrecision, String informationSource, String prescriber,
                             String adherence, String notes, UUID indicationProblemId, UUID prescriptionId,
                             OffsetDateTime clinicalTime, UUID encounterId) {}
    record MedicationUseRevision(long version, MedicationUseData data, String reason, UUID actorId,
                                 OffsetDateTime receivedAt) {}
    record MedicationUse(UUID id, UUID patientId, long version, String status, UUID createdBy,
                         OffsetDateTime createdAt, MedicationUseData data,
                         List<MedicationUseRevision> revisions, boolean duplicateCandidate) {}
    record CreateMedicationUse(UUID id, MedicationUseData data) {}
    record ReviseMedicationUse(long expectedVersion, String reason, MedicationUseData data) {}

    record ProblemData(String problemDisplay, Code code, String verificationStatus, String onset,
                       String onsetPrecision, String abatement, String abatementPrecision, String severity,
                       String informationSource, String notes, OffsetDateTime clinicalTime, UUID encounterId) {}
    record ProblemRevision(long version, ProblemData data, String reason, UUID actorId, OffsetDateTime receivedAt) {}
    record Problem(UUID id, UUID patientId, long version, String clinicalStatus, int priority,
                   UUID createdBy, OffsetDateTime createdAt, ProblemData data,
                   List<ProblemRevision> revisions, List<UUID> encounterIds, boolean duplicateCandidate) {}
    record CreateProblem(UUID id, int priority, ProblemData data) {}
    record ReviseProblem(long expectedVersion, String reason, Integer priority, ProblemData data) {}

    record Transition(long expectedVersion, String transition, String reason) {}
    record EncounterLink(UUID encounterId, String reason) {}

    record ReviewItem(SourceKind sourceKind, UUID sourceId, long sourceVersion, String decision) {}
    record ReviewKind(SourceKind sourceKind, long sourceGeneration, String assertion) {}
    record ReviewManifest(UUID reviewId, UUID patientId, UUID siteId, String reviewType, UUID encounterId,
                          String outcome, String source, boolean interactionsUnavailableAcknowledged,
                          UUID reviewedBy, OffsetDateTime clientTime, OffsetDateTime reviewedAt,
                          String manifestHash, List<ReviewKind> kinds, List<ReviewItem> items,
                          List<ReconciliationDecision> decisions, boolean stale) {}
    record ReviewCommand(UUID reviewId, String outcome, String source,
                         boolean interactionsUnavailableAcknowledged, OffsetDateTime clientTime) {}

    record Proposal(UUID proposalId, UUID patientId, UUID siteId, SourceKind sourceKind,
                    String proposedAction, UUID targetId, JsonNode payload, String informationSource,
                    UUID proposedBy, OffsetDateTime proposedAt, String decision, String decisionReason) {}
    record Propose(UUID proposalId, SourceKind sourceKind, String proposedAction, UUID targetId,
                   JsonNode payload, String informationSource) {}
    record ReconciliationDecision(UUID proposalId, SourceKind sourceKind, UUID sourceId,
                                  long expectedVersion, String decision, JsonNode replacement,
                                  String reason) {
        public ReconciliationDecision {
            if (replacement != null && replacement.isNull()) replacement = null;
        }
    }
    record Reconcile(UUID reviewId, UUID encounterId, Map<SourceKind, Long> expectedGenerations,
                     List<ReconciliationDecision> decisions, String source,
                     boolean interactionsUnavailableAcknowledged, OffsetDateTime clientTime) {}

    record SafetyEntry(UUID id, long version, String display, Code code) {}
    record SafetyManifest(UUID reviewId, String manifestHash, OffsetDateTime reviewedAt,
                          String allergyAssertion, List<SafetyEntry> allergies,
                          List<SafetyEntry> medications, boolean interactionsUnavailableAcknowledged) {}

    ClinicalSummary summary(Context context, UUID patientId);
    Page<Allergy> allergies(Context context, UUID patientId, String status, int page, int pageSize);
    Allergy allergy(Context context, UUID patientId, UUID allergyId);
    Allergy createAllergy(Context context, UUID patientId, UUID idempotencyKey, CreateAllergy command);
    Allergy reviseAllergy(Context context, UUID patientId, UUID allergyId, UUID idempotencyKey, ReviseAllergy command);
    Allergy transitionAllergy(Context context, UUID patientId, UUID allergyId, UUID idempotencyKey, Transition command);
    ReviewManifest reviewAllergies(Context context, UUID patientId, UUID idempotencyKey, ReviewCommand command);

    Page<MedicationUse> medicationUse(Context context, UUID patientId, String status, int page, int pageSize);
    MedicationUse medicationUse(Context context, UUID patientId, UUID medicationUseId);
    MedicationUse createMedicationUse(Context context, UUID patientId, UUID idempotencyKey, CreateMedicationUse command);
    MedicationUse reviseMedicationUse(Context context, UUID patientId, UUID medicationUseId, UUID idempotencyKey, ReviseMedicationUse command);
    MedicationUse transitionMedicationUse(Context context, UUID patientId, UUID medicationUseId, UUID idempotencyKey, Transition command);
    ReviewManifest reviewMedications(Context context, UUID patientId, UUID idempotencyKey, ReviewCommand command);

    Page<Problem> problems(Context context, UUID patientId, String status, int page, int pageSize);
    Problem problem(Context context, UUID patientId, UUID problemId);
    Problem createProblem(Context context, UUID patientId, UUID idempotencyKey, CreateProblem command);
    Problem reviseProblem(Context context, UUID patientId, UUID problemId, UUID idempotencyKey, ReviseProblem command);
    Problem transitionProblem(Context context, UUID patientId, UUID problemId, UUID idempotencyKey, Transition command);
    Problem linkProblem(Context context, UUID patientId, UUID problemId, UUID idempotencyKey, EncounterLink command);
    ReviewManifest reviewProblems(Context context, UUID patientId, UUID idempotencyKey, ReviewCommand command);

    Proposal propose(Context context, UUID patientId, UUID idempotencyKey, Propose command);
    List<Proposal> proposals(Context context, UUID patientId);
    ReviewManifest reconcile(Context context, UUID patientId, UUID idempotencyKey, Reconcile command);
    List<ReviewManifest> reviews(Context context, UUID patientId, UUID encounterId);
    /** Historical links and reconciliations keep a draft from being withdrawn. */
    boolean hasEncounterEvidence(Context context, UUID patientId, UUID encounterId);
    ReviewManifest requireCurrentReview(Context context, UUID patientId, UUID reviewId);
    SafetyManifest requireCurrentSafety(Context context, UUID patientId, UUID reviewId);
    List<Code> terminology(Context context, SourceKind kind, String query);
}
