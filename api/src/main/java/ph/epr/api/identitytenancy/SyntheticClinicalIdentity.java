package ph.epr.api.identitytenancy;

import java.util.UUID;

/** Local opt-in development setup; never a production membership-management API. */
public interface SyntheticClinicalIdentity {
    void prepare(UUID userId, String username, String password);
    void grant(UUID userId, UUID practiceId, UUID siteId, UUID grantingUserId);
}
