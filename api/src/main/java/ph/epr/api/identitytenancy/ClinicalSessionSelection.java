package ph.epr.api.identitytenancy;
import java.util.UUID;
public interface ClinicalSessionSelection {
    Selection current(UUID clinician);
    Selection select(UUID clinician, UUID practice, UUID session, long expectedVersion);
    void requireSelected(UUID clinician, UUID practice, UUID session);
    record Selection(UUID practiceId, UUID sessionId, long version) {}
}
