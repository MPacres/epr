package ph.epr.api.platformoperations.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.*;

@Service
public class TenantSchemaUpgradeService {
    private final ClinicalSchemaRollout rollout;
    private final PracticeProvisioningRegistry registry;
    private final TenantDatabaseManager databases;
    public TenantSchemaUpgradeService(ClinicalSchemaRollout rollout, PracticeProvisioningRegistry registry, TenantDatabaseManager databases) {
        this.rollout = rollout; this.registry = registry; this.databases = databases;
    }
    public Result upgrade(PlatformUser actor, UUID practice, UUID operation, long expectedVersion, String reason) {
        if (actor == null || actor.role() != PlatformRole.SUPERADMIN || !actor.enabled() || actor.passwordResetRequired()) throw new ClinicalAccessException("SCHEMA_ADMINISTRATION_DENIED", 403);
        if (operation == null || reason == null || reason.isBlank() || reason.length() > 500) throw new ClinicalAccessException("MIGRATION_REASON_REQUIRED", 422);
        var claim = rollout.begin(practice, operation, expectedVersion, actor.id(), reason, "11");
        if (!claim.required()) return new Result("READY", "11", claim.attempt());
        try {
            var tenant = registry.findByPracticeId(practice).orElseThrow();
            var version = databases.upgrade(tenant);
            if (!"11".equals(version)) throw new ClinicalAccessException("UNSUPPORTED_SCHEMA_VERSION", 503);
            rollout.finish(practice, operation, claim.attempt(), actor.id(), version, true);
            return new Result("READY", version, claim.attempt());
        } catch (RuntimeException e) {
            rollout.finish(practice, operation, claim.attempt(), actor.id(), "11", false);
            throw new ClinicalAccessException("TENANT_MIGRATION_QUARANTINED", 503);
        }
    }
    public record Result(String state, String schemaVersion, int attempt) {}
}
