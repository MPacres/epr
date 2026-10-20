package ph.epr.api.identitytenancy;

import java.util.List;

/** Bounded control-plane readiness for the local synthetic clinical slice. */
public record ClinicalReadiness(
    State state,
    String currentSchemaVersion,
    String requiredSchemaVersion,
    String rolloutState,
    boolean clinicalEnabled,
    List<SourceCapability> sourceCapabilities,
    String latestFailure
) {
    public static final String REQUIRED_SCHEMA_VERSION = "15";

    public ClinicalReadiness {
        sourceCapabilities = sourceCapabilities == null ? List.of() : List.copyOf(sourceCapabilities);
    }

    public enum State {
        READY,
        SCHEMA_UPGRADE_REQUIRED,
        CLINICAL_DISABLED,
        SOURCE_CAPABILITY_DISABLED,
        MIGRATION_IN_PROGRESS,
        QUARANTINED
    }

    public enum SourceKind { ALLERGY, MEDICATION_USE, PROBLEM }

    public record SourceCapability(SourceKind sourceKind, boolean enabled) {}

    public static ClinicalReadiness evaluate(
        String currentSchemaVersion,
        String rolloutState,
        boolean clinicalEnabled,
        List<SourceCapability> sourceCapabilities,
        boolean tenantQuarantined,
        String latestFailure
    ) {
        var capabilities = sourceCapabilities == null ? List.<SourceCapability>of() : List.copyOf(sourceCapabilities);
        var state = tenantQuarantined || "QUARANTINED".equals(rolloutState)
            ? State.QUARANTINED
            : "MIGRATING".equals(rolloutState)
                ? State.MIGRATION_IN_PROGRESS
                : !REQUIRED_SCHEMA_VERSION.equals(currentSchemaVersion)
                    ? State.SCHEMA_UPGRADE_REQUIRED
                    : !clinicalEnabled
                        ? State.CLINICAL_DISABLED
                        : allSourcesEnabled(capabilities) ? State.READY : State.SOURCE_CAPABILITY_DISABLED;
        return new ClinicalReadiness(state, currentSchemaVersion, REQUIRED_SCHEMA_VERSION, rolloutState,
            clinicalEnabled, capabilities, latestFailure);
    }

    private static boolean allSourcesEnabled(List<SourceCapability> capabilities) {
        return List.of(SourceKind.values()).stream().allMatch(kind -> capabilities.stream()
            .anyMatch(capability -> capability.sourceKind() == kind && capability.enabled()));
    }
}
