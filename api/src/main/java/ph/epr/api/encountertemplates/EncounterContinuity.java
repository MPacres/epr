package ph.epr.api.encountertemplates;
import java.time.LocalDate;
import java.util.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
/** Authorized snapshots from the source module, never direct repository access by consumers. */
public interface EncounterContinuity {
    List<Obligation> obligations(Context context);
    record Obligation(UUID sourceId, long sourceVersion, UUID patientId, String patientName, String recordNumber, UUID encounterId, UUID ownerId,
                      String kind, String instruction, LocalDate dueDate, boolean open) {}
}
