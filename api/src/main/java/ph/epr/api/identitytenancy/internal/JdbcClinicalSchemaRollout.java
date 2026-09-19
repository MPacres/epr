package ph.epr.api.identitytenancy.internal;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import ph.epr.api.identitytenancy.*;

@Service
class JdbcClinicalSchemaRollout implements ClinicalSchemaRollout {
    private final JdbcClient jdbc;
    JdbcClinicalSchemaRollout(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim begin(UUID practice, UUID operation, long expectedVersion, UUID actor, String reason, String target) {
        scope();
        var version = jdbc.sql("SELECT version FROM practice_tenant_registry WHERE practice_id = :practice AND provisioning_status = 'ACTIVE' FOR UPDATE")
            .param("practice", practice).query(Long.class).optional().orElseThrow(() -> new ClinicalAccessException("TENANT_UNAVAILABLE", 404));
        var previous = jdbc.sql("SELECT operation_id, attempt, state, lease_until, target_version, actor_id, reason FROM clinical_schema_rollout WHERE practice_id = :practice")
            .param("practice", practice).query((rs, n) -> new Previous(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7))).optional();
        if (previous.isPresent() && previous.get().operation().equals(operation)
            && (!previous.get().target().equals(target) || !previous.get().actor().equals(actor) || !previous.get().reason().equals(reason)))
            throw new ClinicalAccessException("IDEMPOTENCY_PAYLOAD_CONFLICT", 409);
        if (previous.isPresent() && previous.get().operation().equals(operation) && previous.get().state().equals("READY")) return new Claim(previous.get().attempt(), false);
        if (previous.isPresent() && previous.get().state().equals("MIGRATING") && previous.get().lease().isAfter(OffsetDateTime.now())) throw new ClinicalAccessException("MIGRATION_IN_PROGRESS", 409);
        if (version != expectedVersion) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
        var attempt = previous.map(p -> p.attempt() + 1).orElse(1);
        jdbc.sql("""
            INSERT INTO clinical_schema_rollout(practice_id, operation_id, attempt, state, lease_until, target_version, actor_id, reason)
            VALUES (:practice, :operation, :attempt, 'MIGRATING', CURRENT_TIMESTAMP + INTERVAL '5 minutes', :target, :actor, :reason)
            ON CONFLICT (practice_id) DO UPDATE SET operation_id = EXCLUDED.operation_id, attempt = EXCLUDED.attempt, state = 'MIGRATING',
                lease_until = EXCLUDED.lease_until, target_version = EXCLUDED.target_version, actor_id = EXCLUDED.actor_id, reason = EXCLUDED.reason, updated_at = CURRENT_TIMESTAMP
            """).param("practice", practice).param("operation", operation).param("attempt", attempt).param("target", target).param("actor", actor).param("reason", reason).update();
        event(practice, operation, attempt, actor, "MIGRATING");
        return new Claim(attempt, true);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID practice, UUID operation, int attempt, UUID actor, String version, boolean success) {
        scope();
        var updated = jdbc.sql("""
            UPDATE clinical_schema_rollout SET state = :state, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE practice_id = :practice AND operation_id = :operation AND attempt = :attempt AND state = 'MIGRATING'
            """).param("state", success ? "READY" : "QUARANTINED").param("practice", practice).param("operation", operation).param("attempt", attempt).update();
        if (updated != 1) throw new ClinicalAccessException("MIGRATION_CLAIM_SUPERSEDED", 409);
        if (success) jdbc.sql("UPDATE practice_tenant_registry SET schema_version = :version, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE practice_id = :practice")
            .param("version", version).param("practice", practice).update();
        event(practice, operation, attempt, actor, success ? "READY" : "QUARANTINED");
    }
    private void event(UUID practice, UUID operation, int attempt, UUID actor, String state) {
        jdbc.sql("INSERT INTO clinical_schema_rollout_event(id, practice_id, operation_id, attempt, state, actor_id) VALUES (:id, :practice, :operation, :attempt, :state, :actor)")
            .param("id", UUID.randomUUID()).param("practice", practice).param("operation", operation).param("attempt", attempt).param("state", state).param("actor", actor).update();
    }
    private void scope() { jdbc.sql("SELECT set_config('epr.platform_operations', 'true', true)").query(String.class).single(); }
    private record Previous(UUID operation, int attempt, String state, OffsetDateTime lease, String target, UUID actor, String reason) {}
}
