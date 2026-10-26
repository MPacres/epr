package ph.epr.api.encountertemplates;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import ph.epr.api.encountertemplates.EncounterInterventionLinks.Item;
import ph.epr.api.encountertemplates.EncounterInterventionLinks.Kind;
import ph.epr.api.platformoperations.TenantTransactions.Context;

/** Source modules validate and issue their own draft documents in the note-signing transaction. */
public interface EncounterSigningDocumentIssuer {
    boolean supports(Kind kind);
    void checkForSigning(Context context, UUID patient, UUID encounter, Item item);
    void issueWithSignature(Context context, UUID patient, UUID encounter, Item item, UUID signaturePreparationId, String duplicateIntentReason);

    static UUID key(UUID preparation, Kind kind, UUID source, String step) {
        return UUID.nameUUIDFromBytes((preparation + "/" + kind + "/" + source + "/" + step).getBytes(StandardCharsets.UTF_8));
    }
}
