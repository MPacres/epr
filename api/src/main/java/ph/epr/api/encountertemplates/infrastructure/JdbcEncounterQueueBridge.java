package ph.epr.api.encountertemplates.infrastructure;

import java.util.UUID;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterQueueBridge;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
class JdbcEncounterQueueBridge implements EncounterQueueBridge {
    private final JdbcEncounterService encounters;
    JdbcEncounterQueueBridge(JdbcEncounterService encounters) { this.encounters = encounters; }
    public Created start(Context c, UUID patient, UUID key, Start request) {
        var result = encounters.createQueued(c, patient, key, request);
        return new Created(result.encounterId(), result.version(), result.careState());
    }
    public Created current(Context c, UUID patient, UUID id) {
        var result = encounters.get(c, patient, id, false);
        return new Created(result.encounterId(), result.version(), result.careState());
    }
}
