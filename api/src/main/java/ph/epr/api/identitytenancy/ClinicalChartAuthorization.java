package ph.epr.api.identitytenancy;

import java.util.List;
import java.util.UUID;

/** Action-specific authority for longitudinal clinical sources. */
public interface ClinicalChartAuthorization {
    enum SourceKind { ALLERGY, MEDICATION_USE, PROBLEM }
    enum Action { READ, PROPOSE, WRITE, REVIEW }
    record Capability(SourceKind sourceKind, boolean read, boolean propose, boolean write, boolean review) {
        public boolean allows(Action action) {
            return read && switch (action) {
                case READ -> true;
                case PROPOSE -> propose;
                case WRITE -> write;
                case REVIEW -> review;
            };
        }
    }
    List<Capability> capabilities(UUID actorId, UUID practiceId, UUID siteId);
    default void require(UUID actorId, UUID practiceId, UUID siteId, SourceKind kind, Action action) {
        if (actorId == null || practiceId == null || siteId == null || kind == null || action == null
            || capabilities(actorId, practiceId, siteId).stream().noneMatch(capability ->
                capability.sourceKind() == kind && capability.allows(action))) {
            throw new ClinicalAccessException("CLINICAL_CHART_ACCESS_DENIED", 403);
        }
    }
}
