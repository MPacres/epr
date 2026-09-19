package ph.epr.api.identitytenancy;
import java.util.UUID;
public interface ClinicalSchemaRollout {
    Claim begin(UUID practice, UUID operation, long expectedVersion, UUID actor, String reason, String target);
    void finish(UUID practice, UUID operation, int attempt, UUID actor, String version, boolean success);
    record Claim(int attempt, boolean required) {}
}
