package ph.epr.api.identitytenancy.internal;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.*;

@Service
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
class JdbcSyntheticClinicalCapability implements SyntheticClinicalCapability {
    private final JdbcClient jdbc;

    JdbcSyntheticClinicalCapability(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public Result enable(PlatformUser actor, UUID practiceId, UUID operationId, long expectedVersion, String reason) {
        if (actor == null || actor.role() != PlatformRole.SUPERADMIN || !actor.enabled() || actor.passwordResetRequired())
            throw new ClinicalAccessException("CLINICAL_ENABLEMENT_DENIED", 403);
        if (practiceId == null || operationId == null || expectedVersion < 0 || reason == null || reason.isBlank() || reason.length() > 500)
            throw new ClinicalAccessException("CLINICAL_ENABLEMENT_REQUEST_INVALID", 422);
        var normalizedReason = reason.strip();
        jdbc.sql("SELECT set_config('epr.platform_operations', 'true', true)").query(String.class).single();
        var practice = jdbc.sql("""
            SELECT version, schema_version, provisioning_status, service_status
            FROM practice_tenant_registry WHERE practice_id = :practice FOR UPDATE
            """).param("practice", practiceId).query((rs, n) -> new PracticeState(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            .optional().orElseThrow(() -> new ClinicalAccessException("TENANT_UNAVAILABLE", 404));
        var prior = jdbc.sql("""
            SELECT practice_id, actor_user_id, reason, resulting_version, event_type
            FROM practice_administration_event WHERE idempotency_key = :operation
            """).param("operation", operationId).query((rs, n) -> new Prior(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4), rs.getString(5)))
            .optional();
        if (prior.isPresent()) {
            var event = prior.get();
            if (!event.practiceId().equals(practiceId) || !event.actorId().equals(actor.id())
                || !event.reason().equals(normalizedReason) || event.version() != expectedVersion + 1
                || !event.type().equals("SYNTHETIC_CLINICAL_ENABLED"))
                throw new ClinicalAccessException("IDEMPOTENCY_PAYLOAD_CONFLICT", 409);
            return new Result(true, event.version(), true);
        }
        if (practice.version() != expectedVersion) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
        if (!practice.schemaVersion().equals("15") || !practice.provisioningStatus().equals("ACTIVE")
            || !practice.serviceStatus().equals("ENABLED")
            || jdbc.sql("SELECT EXISTS(SELECT 1 FROM clinical_schema_rollout WHERE practice_id = :practice AND state <> 'READY')")
                .param("practice", practiceId).query(Boolean.class).single())
            throw new ClinicalAccessException("CLINICAL_SCHEMA_NOT_READY", 409);
        var grantExists = jdbc.sql("""
            SELECT EXISTS(
                SELECT 1 FROM practice_membership m
                JOIN practice_role_assignment r ON r.practice_id = m.practice_id AND r.user_id = m.user_id
                    AND r.practice_role = 'CLINICIAN' AND r.enabled
                JOIN practice_clinician_site_grant g ON g.practice_id = m.practice_id AND g.user_id = m.user_id
                    AND g.enabled AND g.patient_directory_read AND g.patient_detail_read
                JOIN platform_user_account u ON u.id = m.user_id
                WHERE m.practice_id = :practice AND m.enabled AND u.enabled AND NOT u.password_reset_required
                    AND u.role IN ('CLINICIAN', 'PRACTICE_STAFF')
            )
            """).param("practice", practiceId).query(Boolean.class).single();
        if (!grantExists) throw new ClinicalAccessException("CLINICIAN_SITE_GRANT_REQUIRED", 409);
        var alreadyEnabled = jdbc.sql("SELECT enabled FROM practice_clinical_capability WHERE practice_id = :practice")
            .param("practice", practiceId).query(Boolean.class).optional().orElse(false);
        if (alreadyEnabled) throw new ClinicalAccessException("CLINICAL_CAPABILITY_ALREADY_ENABLED", 409);
        jdbc.sql("""
            INSERT INTO practice_clinical_capability(practice_id, enabled)
            VALUES (:practice, true)
            ON CONFLICT (practice_id) DO UPDATE SET enabled = true, updated_at = CURRENT_TIMESTAMP
            """).param("practice", practiceId).update();
        jdbc.sql("""
            INSERT INTO practice_clinical_chart_capability(practice_id, source_kind, enabled)
            SELECT :practice, source_kind, true
            FROM (VALUES ('ALLERGY'), ('MEDICATION_USE'), ('PROBLEM')) sources(source_kind)
            ON CONFLICT (practice_id, source_kind) DO UPDATE SET enabled = true
            """).param("practice", practiceId).update();
        jdbc.sql("UPDATE practice_tenant_registry SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE practice_id = :practice")
            .param("practice", practiceId).update();
        jdbc.sql("""
            INSERT INTO practice_administration_event(id, practice_id, event_type, actor_user_id, reason,
                previous_value, next_value, idempotency_key, resulting_version, occurred_at)
            VALUES (:id, :practice, 'SYNTHETIC_CLINICAL_ENABLED', :actor, :reason,
                'DISABLED', 'ENABLED', :operation, :version, CURRENT_TIMESTAMP)
            """).param("id", UUID.randomUUID()).param("practice", practiceId).param("actor", actor.id())
            .param("reason", normalizedReason).param("operation", operationId).param("version", expectedVersion + 1).update();
        return new Result(true, expectedVersion + 1, false);
    }

    private record PracticeState(long version, String schemaVersion, String provisioningStatus, String serviceStatus) {}
    private record Prior(UUID practiceId, UUID actorId, String reason, long version, String type) {}
}
