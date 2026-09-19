package ph.epr.api.platformoperations;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
public class TenantCommandJournal {
    /** Call only after current authorization and record scope checks, including on replay. */
    public <T> T execute(Context context, String operation, UUID key, Object request, Class<T> resultType, Supplier<T> command) {
        if (key == null) throw new ClinicalAccessException("IDEMPOTENCY_KEY_REQUIRED", 422);
        var jdbc = context.jdbc();
        var lock = context.practiceId() + "/" + context.actorId() + "/" + operation + "/" + key;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", lock)
            .query((rs, n) -> true).single();
        var hash = ClinicalJson.hash(request);
        var existing = jdbc.sql("""
            SELECT request_hash, outcome::text FROM tenant_command_receipt
            WHERE practice_id = :practice AND actor_id = :actor AND operation = :operation AND idempotency_key = :key
            """).param("practice", context.practiceId()).param("actor", context.actorId())
            .param("operation", operation).param("key", key)
            .query((rs, n) -> new Receipt(rs.getString(1), rs.getString(2))).optional();
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().hash())) throw new ClinicalAccessException("IDEMPOTENCY_PAYLOAD_CONFLICT", 409);
            return ClinicalJson.read(existing.get().outcome(), resultType);
        }
        var result = command.get();
        jdbc.sql("""
            INSERT INTO tenant_command_receipt(practice_id, actor_id, operation, idempotency_key, request_hash, outcome)
            VALUES (:practice, :actor, :operation, :key, :hash, CAST(:outcome AS jsonb))
            """).param("practice", context.practiceId()).param("actor", context.actorId()).param("operation", operation)
            .param("key", key).param("hash", hash).param("outcome", ClinicalJson.write(result)).update();
        return result;
    }

    public void audit(Context context, UUID recordId, String action, String reason) {
        context.jdbc().sql("""
            INSERT INTO clinical_audit_event(id, practice_id, actor_id, record_id, action, reason)
            VALUES (:id, :practice, :actor, :record, :action, :reason)
            """).param("id", UUID.randomUUID()).param("practice", context.practiceId()).param("actor", context.actorId())
            .param("record", recordId).param("action", action).param("reason", reason, java.sql.Types.VARCHAR).update();
    }

    public void event(Context context, UUID recordId, String type, UUID ownerId) {
        context.jdbc().sql("""
            INSERT INTO clinical_outbox_event(id, practice_id, aggregate_id, event_type, owner_id)
            VALUES (:id, :practice, :record, :type, :owner) ON CONFLICT DO NOTHING
            """).param("id", UUID.randomUUID()).param("practice", context.practiceId()).param("record", recordId)
            .param("type", type).param("owner", ownerId).update();
    }
    public void acknowledgeEvents(Context c, java.util.List<UUID> sources) {
        for (var source : sources) c.jdbc().sql("UPDATE clinical_outbox_event SET delivered_at = CURRENT_TIMESTAMP, attempts = attempts + 1 WHERE practice_id = :practice AND owner_id = :owner AND aggregate_id = :source AND delivered_at IS NULL")
            .param("practice", c.practiceId()).param("owner", c.actorId()).param("source", source).update();
    }
    private record Receipt(String hash, String outcome) {}
}
