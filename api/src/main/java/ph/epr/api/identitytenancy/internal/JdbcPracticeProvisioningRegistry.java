package ph.epr.api.identitytenancy.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ph.epr.api.identitytenancy.PracticeProvisioningConflictException;
import ph.epr.api.identitytenancy.PracticeProvisioningRegistry;
import ph.epr.api.identitytenancy.PracticeProvisioningRequest;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;

@Repository
class JdbcPracticeProvisioningRegistry implements PracticeProvisioningRegistry {
	private static final Duration PROVISIONING_LEASE = Duration.ofMinutes(15);

	private final JdbcClient jdbcClient;

	JdbcPracticeProvisioningRegistry(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	@Transactional
	public ProvisioningClaim claim(PracticeProvisioningRequest request, OffsetDateTime now) {
		enablePlatformOperationsContext();

		var byIdempotencyKey = findForUpdate("provisioning_idempotency_key", request.idempotencyKey());
		if (byIdempotencyKey.isPresent()) {
			return claimExisting(byIdempotencyKey.get(), request, now);
		}

		var byPracticeId = findForUpdate("practice_id", request.practiceId());
		if (byPracticeId.isPresent()) {
			throw new PracticeProvisioningConflictException(
				"The practice identifier is already registered under a different idempotency key."
			);
		}
		assertEligibleAdministrator(request.initialAdministratorUserId());

		var inserted = jdbcClient.sql("""
				INSERT INTO practice_tenant_registry (
					practice_id, practice_code, display_name, initial_administrator_user_id,
					provisioning_status, provisioning_idempotency_key, request_fingerprint,
					requested_by_user_id, provisioning_lease_expires_at,
					provisioning_attempts, created_at, updated_at, version
				) VALUES (
					:practiceId, :code, :displayName, :administratorId,
					'PROVISIONING', :idempotencyKey, :fingerprint,
					:requestedBy, :leaseExpiresAt, 1, :now, :now, 0
				)
				ON CONFLICT DO NOTHING
				""")
				.param("practiceId", request.practiceId())
				.param("code", request.code())
				.param("displayName", request.displayName())
				.param("administratorId", request.initialAdministratorUserId())
				.param("idempotencyKey", request.idempotencyKey())
				.param("fingerprint", request.requestFingerprint())
				.param("requestedBy", request.requestedByUserId())
				.param("leaseExpiresAt", now.plus(PROVISIONING_LEASE))
				.param("now", now)
				.update();
		if (inserted == 0) {
			var concurrentByKey = findForUpdate("provisioning_idempotency_key", request.idempotencyKey());
			if (concurrentByKey.isPresent()) {
				return claimExisting(concurrentByKey.get(), request, now);
			}
			throw new PracticeProvisioningConflictException(
				"The practice code, practice identifier, or idempotency key is already in use."
			);
		}

		appendEvent(request.practiceId(), 1, "PROVISIONING_STARTED", request.requestedByUserId(), null, now);
		return new ProvisioningClaim(findRequired(request.practiceId()), true);
	}

	private ProvisioningClaim claimExisting(
		PracticeTenant existing,
		PracticeProvisioningRequest request,
		OffsetDateTime now
	) {
		if (!existing.practiceId().equals(request.practiceId())
			|| !existing.code().equals(request.code())
			|| !existing.displayName().equals(request.displayName())
			|| !existing.initialAdministratorUserId().equals(request.initialAdministratorUserId())
			|| !requestFingerprint(existing.practiceId()).equals(request.requestFingerprint())) {
			throw new PracticeProvisioningConflictException(
				"The idempotency key was already used with a different tenant creation request."
			);
		}

		if (existing.status() == PracticeProvisioningStatus.QUARANTINED || leaseExpired(existing.practiceId(), now)) {
			assertEligibleAdministrator(request.initialAdministratorUserId());
			jdbcClient.sql("""
				UPDATE practice_tenant_registry
				SET provisioning_status = 'PROVISIONING',
					provisioning_attempts = provisioning_attempts + 1,
					failure_code = NULL,
					failure_message = NULL,
					provisioning_lease_expires_at = :leaseExpiresAt,
					updated_at = :now,
					version = version + 1
				WHERE practice_id = :practiceId
				""")
				.param("practiceId", existing.practiceId())
				.param("leaseExpiresAt", now.plus(PROVISIONING_LEASE))
				.param("now", now)
				.update();
			var retry = findRequired(existing.practiceId());
			appendEvent(retry.practiceId(), retry.provisioningAttempts(), "PROVISIONING_RETRIED", request.requestedByUserId(), null, now);
			return new ProvisioningClaim(retry, true);
		}

		return new ProvisioningClaim(existing, false);
	}

	@Override
	@Transactional
	public PracticeTenant activate(
		UUID practiceId,
		UUID idempotencyKey,
		int expectedAttempt,
		UUID actorUserId,
		TenantDatabaseRoute route,
		OffsetDateTime now
	) {
		enablePlatformOperationsContext();
		var pending = findForUpdate("practice_id", practiceId)
			.orElseThrow(() -> new IllegalStateException("Tenant provisioning registry entry was not found."));
		assertEligibleAdministrator(pending.initialAdministratorUserId());
		var updated = jdbcClient.sql("""
			UPDATE practice_tenant_registry
			SET provisioning_status = 'ACTIVE',
				database_name = :databaseName,
				jdbc_url = :jdbcUrl,
				runtime_secret_reference = :runtimeSecretReference,
				migration_secret_reference = :migrationSecretReference,
				schema_version = :schemaVersion,
				provisioning_lease_expires_at = NULL,
				failure_code = NULL,
				failure_message = NULL,
				updated_at = :now,
				version = version + 1
			WHERE practice_id = :practiceId
				AND provisioning_idempotency_key = :idempotencyKey
				AND provisioning_attempts = :expectedAttempt
				AND provisioning_status = 'PROVISIONING'
			""")
			.param("databaseName", route.databaseName())
			.param("jdbcUrl", route.jdbcUrl())
			.param("runtimeSecretReference", route.runtimeSecretReference())
			.param("migrationSecretReference", route.migrationSecretReference())
			.param("schemaVersion", route.schemaVersion())
			.param("now", now)
			.param("practiceId", practiceId)
			.param("idempotencyKey", idempotencyKey)
			.param("expectedAttempt", expectedAttempt)
			.update();
		if (updated != 1) {
			throw new IllegalStateException("The tenant provisioning claim is no longer active.");
		}

		var tenant = findRequired(practiceId);
		jdbcClient.sql("""
			INSERT INTO practice_membership (
				practice_id, user_id, enabled, created_at, updated_at
			) VALUES (
				:practiceId, :userId, TRUE, :now, :now
			)
			ON CONFLICT (practice_id, user_id) DO UPDATE SET
				enabled = TRUE,
				updated_at = EXCLUDED.updated_at
			""")
			.param("practiceId", tenant.practiceId())
			.param("userId", tenant.initialAdministratorUserId())
			.param("now", now)
			.update();
		jdbcClient.sql("""
			INSERT INTO practice_role_assignment (
				practice_id, user_id, practice_role, enabled, created_at, updated_at
			) VALUES (
				:practiceId, :userId, 'PRACTICE_ADMINISTRATOR', TRUE, :now, :now
			)
			ON CONFLICT (practice_id, user_id, practice_role) DO UPDATE SET
				enabled = TRUE,
				updated_at = EXCLUDED.updated_at
			""")
			.param("practiceId", tenant.practiceId())
			.param("userId", tenant.initialAdministratorUserId())
			.param("now", now)
			.update();
		appendEvent(practiceId, tenant.provisioningAttempts(), "PROVISIONING_SUCCEEDED", actorUserId, null, now);
		return tenant;
	}

	@Override
	@Transactional
	public PracticeTenant quarantine(
		UUID practiceId,
		UUID idempotencyKey,
		int expectedAttempt,
		UUID actorUserId,
		String failureCode,
		String failureMessage,
		OffsetDateTime now
	) {
		enablePlatformOperationsContext();
		var updated = jdbcClient.sql("""
			UPDATE practice_tenant_registry
			SET provisioning_status = 'QUARANTINED',
				failure_code = :failureCode,
				failure_message = :failureMessage,
				provisioning_lease_expires_at = NULL,
				updated_at = :now,
				version = version + 1
			WHERE practice_id = :practiceId
				AND provisioning_idempotency_key = :idempotencyKey
				AND provisioning_attempts = :expectedAttempt
				AND provisioning_status = 'PROVISIONING'
			""")
			.param("failureCode", failureCode)
			.param("failureMessage", failureMessage)
			.param("now", now)
			.param("practiceId", practiceId)
			.param("idempotencyKey", idempotencyKey)
			.param("expectedAttempt", expectedAttempt)
			.update();
		var tenant = findRequired(practiceId);
		if (updated == 1) {
			appendEvent(practiceId, tenant.provisioningAttempts(), "PROVISIONING_QUARANTINED", actorUserId, failureCode, now);
		}
		return tenant;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PracticeTenant> findByPracticeId(UUID practiceId) {
		enablePlatformOperationsContext();
		return jdbcClient.sql("""
			SELECT practice_id, practice_code, display_name, initial_administrator_user_id,
				provisioning_status, database_name, jdbc_url, runtime_secret_reference,
				migration_secret_reference, schema_version, provisioning_attempts,
				failure_code, failure_message, created_at, updated_at, version
			FROM practice_tenant_registry
			WHERE practice_id = :practiceId
			""")
			.param("practiceId", practiceId)
			.query(this::mapTenant)
			.optional();
	}

	private Optional<PracticeTenant> findForUpdate(String column, UUID value) {
		if (!column.equals("practice_id") && !column.equals("provisioning_idempotency_key")) {
			throw new IllegalArgumentException("Unsupported tenant lookup column.");
		}
		return jdbcClient.sql("""
			SELECT practice_id, practice_code, display_name, initial_administrator_user_id,
				provisioning_status, database_name, jdbc_url, runtime_secret_reference,
				migration_secret_reference, schema_version, provisioning_attempts,
				failure_code, failure_message, created_at, updated_at, version
			FROM practice_tenant_registry
			WHERE %s = :value
			FOR UPDATE
			""".formatted(column))
			.param("value", value)
			.query(this::mapTenant)
			.optional();
	}

	private String requestFingerprint(UUID practiceId) {
		return jdbcClient.sql("""
			SELECT request_fingerprint
			FROM practice_tenant_registry
			WHERE practice_id = :practiceId
			""")
			.param("practiceId", practiceId)
			.query(String.class)
			.single();
	}

	private boolean leaseExpired(UUID practiceId, OffsetDateTime now) {
		return jdbcClient.sql("""
			SELECT provisioning_status = 'PROVISIONING'
				AND provisioning_lease_expires_at <= :now
			FROM practice_tenant_registry
			WHERE practice_id = :practiceId
			""")
			.param("practiceId", practiceId)
			.param("now", now)
			.query(Boolean.class)
			.single();
	}

	private PracticeTenant findRequired(UUID practiceId) {
		return findByPracticeId(practiceId)
			.orElseThrow(() -> new IllegalStateException("Tenant registry entry disappeared."));
	}

	private void assertEligibleAdministrator(UUID userId) {
		var role = jdbcClient.sql("""
			SELECT role
			FROM platform_user_account
			WHERE id = :userId AND enabled = TRUE
			""")
			.param("userId", userId)
			.query(String.class)
			.optional();
		if (role.isEmpty() || (!role.get().equals("CLINICIAN") && !role.get().equals("PRACTICE_STAFF"))) {
			throw new PracticeProvisioningConflictException(
				"The initial practice administrator must be an enabled practice user."
			);
		}
	}

	private void enablePlatformOperationsContext() {
		jdbcClient.sql("SELECT set_config('epr.platform_operations', 'true', true)")
			.query(String.class)
			.single();
	}

	private void appendEvent(
		UUID practiceId,
		int attempt,
		String eventType,
		UUID actorUserId,
		String detailCode,
		OffsetDateTime occurredAt
	) {
		jdbcClient.sql("""
			INSERT INTO tenant_provisioning_event (
				id, practice_id, attempt, event_type, actor_user_id, detail_code, occurred_at
			) VALUES (
				:id, :practiceId, :attempt, :eventType, :actorUserId, :detailCode, :occurredAt
			)
			""")
			.param("id", UUID.randomUUID())
			.param("practiceId", practiceId)
			.param("attempt", attempt)
			.param("eventType", eventType)
			.param("actorUserId", actorUserId)
			.param("detailCode", detailCode)
			.param("occurredAt", occurredAt)
			.update();
	}

	private PracticeTenant mapTenant(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PracticeTenant(
			resultSet.getObject("practice_id", UUID.class),
			resultSet.getString("practice_code"),
			resultSet.getString("display_name"),
			resultSet.getObject("initial_administrator_user_id", UUID.class),
			PracticeProvisioningStatus.valueOf(resultSet.getString("provisioning_status")),
			resultSet.getString("database_name"),
			resultSet.getString("jdbc_url"),
			resultSet.getString("runtime_secret_reference"),
			resultSet.getString("migration_secret_reference"),
			resultSet.getString("schema_version"),
			resultSet.getInt("provisioning_attempts"),
			resultSet.getString("failure_code"),
			resultSet.getString("failure_message"),
			resultSet.getObject("created_at", OffsetDateTime.class),
			resultSet.getObject("updated_at", OffsetDateTime.class),
			resultSet.getLong("version")
		);
	}
}
