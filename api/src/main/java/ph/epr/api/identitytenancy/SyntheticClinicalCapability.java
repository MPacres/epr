package ph.epr.api.identitytenancy;

import java.util.UUID;

/** Local synthetic Practice enablement. A clinician role or site grant never enables a Practice by itself. */
public interface SyntheticClinicalCapability {
    Result enable(PlatformUser actor, UUID practiceId, UUID operationId, long expectedVersion, String reason);

    record Result(boolean enabled, long version, boolean replayed) {}
}
