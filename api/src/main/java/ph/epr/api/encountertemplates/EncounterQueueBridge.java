package ph.epr.api.encountertemplates;

import java.time.OffsetDateTime;
import java.util.UUID;
import ph.epr.api.platformoperations.TenantTransactions.Context;

public interface EncounterQueueBridge {
    Created start(Context context, UUID patientId, UUID key, Start request);
    Created current(Context context, UUID patientId, UUID encounterId);
    record Start(UUID encounterId, UUID releaseId, UUID episodeId, String careMode, OffsetDateTime clinicalTime,
                 UUID sessionId, UUID queueEntryId, long queueVersion) {}
    record Created(UUID encounterId, long version, String careState) {}
}
