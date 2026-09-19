package ph.epr.api.platformoperations;

import java.util.UUID;

public interface SyntheticClinicalPractice {
    void prepare(UUID clinicianId, UUID practiceId, UUID siteId, String username);
}
