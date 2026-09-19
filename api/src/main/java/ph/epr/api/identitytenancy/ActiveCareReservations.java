package ph.epr.api.identitytenancy;

import java.util.UUID;

/** No lease expiry: uncertain care requires verified tenant reconciliation. */
public interface ActiveCareReservations {
    long reserve(UUID clinician, UUID practice, UUID encounter, String requestHash);
    long reserveQueued(UUID clinician, UUID practice, UUID encounter, String requestHash, UUID session);
    java.util.OptionalLong fence(UUID clinician, UUID practice, UUID encounter);
    void release(UUID clinician, UUID practice, UUID encounter, long fence);
}
