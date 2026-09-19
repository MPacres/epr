package ph.epr.api.clinicalchart;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ph.epr.api.platformoperations.TenantTransactions.Context;

public interface PregnancyRecords {
    List<Episode> list(Context context, UUID patientId);
    Episode create(Context context, UUID patientId, UUID key, CreateEpisode request);
    List<DatingAssessment> dating(Context context, UUID patientId, UUID episodeId);
    Episode addDating(Context context, UUID patientId, UUID episodeId, UUID key, AddDating request);
    Episode selectDating(Context context, UUID patientId, UUID episodeId, UUID key, SelectDating request);
    Snapshot snapshot(Context context, UUID patientId, UUID episodeId);
    List<History> history(Context context, UUID patientId);
    History saveHistory(Context context, UUID patientId, UUID key, SaveHistory request);

    record Episode(UUID episodeId, String status, UUID responsibleClinicianId, String source,
                   OffsetDateTime clinicalTime, UUID selectedDatingId, long version, UUID createdBy, OffsetDateTime receivedAt) {}
    record CreateEpisode(UUID episodeId, String status, String source, OffsetDateTime clinicalTime) {}
    record DatingAssessment(UUID assessmentId, LocalDate estimatedDate, LocalDate sourceDate, String sourcePrecision,
                            String method, String source, String reviewStatus, UUID createdBy, OffsetDateTime receivedAt) {}
    record AddDating(UUID assessmentId, long expectedVersion, LocalDate estimatedDate, LocalDate sourceDate,
                     String sourcePrecision, String method, String source, String reviewStatus) {}
    record SelectDating(UUID assessmentId, long expectedVersion, String reason) {}
    record Snapshot(Episode episode, DatingAssessment selectedDating) {}
    record History(UUID itemId, long version, String account, String source, String reviewStatus,
                   String reason, UUID actorId, OffsetDateTime receivedAt) {}
    record SaveHistory(UUID itemId, long expectedVersion, String account, String source, String reviewStatus, String reason) {}
}
