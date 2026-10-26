package ph.epr.api.identitytenancy;

import java.util.Optional;
import java.util.UUID;

/** Practice-scoped display identity for human-readable clinical documents. */
public interface ClinicalDocumentIdentity {
    Optional<String> clinicianName(UUID practiceId, UUID userId);
}
