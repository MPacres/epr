package ph.epr.api.identitytenancy.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ph.epr.api.identitytenancy.PracticeAdministrationConflictException;
import ph.epr.api.identitytenancy.PracticeAdministrationPage;
import ph.epr.api.identitytenancy.PracticeAdministrationRegistry;
import ph.epr.api.identitytenancy.PracticeAdministrationView;
import ph.epr.api.identitytenancy.PracticeProvisioningConflictException;
import ph.epr.api.identitytenancy.PracticeProvisioningRegistry;
import ph.epr.api.identitytenancy.PracticeProvisioningRequest;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;

@Repository
class JdbcPracticeProvisioningRegistry implements PracticeProvisioningRegistry, PracticeAdministrationRegistry {
	private static final Duration PROVISIONING_LEASE = Duration.ofMinutes(15);
	private static final String TENANT_COLUMNS = """
		r.practice_id, r.practice_code, r.display_name, r.initial_administrator_user_id,
		r.provisioning_status, r.service_status, r.database_name, r.jdbc_url,
		r.runtime_secret_reference, r.migration_secret_reference, r.schema_version,
		r.provisioning_attempts, r.failure_code, r.failure_message, r.suspended_at,
		r.suspension_reason, r.suspended_by_user_id, r.created_at, r.updated_at, r.version
		""";

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
		assertEligibleAdministrator(request.practiceId(), request.initialAdministratorUserId());

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
			assertEligibleAdministrator(request.practiceId(), request.initialAdministratorUserId());
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
		assertEligibleAdministrator(pending.practiceId(), pending.initialAdministratorUserId());
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
		return jdbcClient.sql("SELECT " + TENANT_COLUMNS + " FROM practice_tenant_registry r WHERE r.practice_id = :practiceId")
			.param("practiceId", practiceId)
			.query(this::mapTenant)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public PracticeAdministrationPage list(
		String search,
		PracticeProvisioningStatus provisioningStatus,
		PracticeServiceStatus serviceStatus,
		int page,
		int size
	) {
		enablePlatformOperationsContext();
		var where = new StringBuilder(" WHERE TRUE");
		var parameters = new HashMap<String, Object>();
		if (search != null && !search.isBlank()) {
			where.append(" AND (r.display_name ILIKE :search OR r.practice_code ILIKE :search OR r.practice_id::text ILIKE :search)");
			parameters.put("search", "%" + escapeLike(search.strip()) + "%");
		}
		if (provisioningStatus != null) {
			where.append(" AND r.provisioning_status = :provisioningStatus");
			parameters.put("provisioningStatus", provisioningStatus.name());
		}
		if (serviceStatus != null) {
			where.append(" AND r.service_status = :serviceStatus");
			parameters.put("serviceStatus", serviceStatus.name());
		}

		var total = jdbcClient.sql("SELECT count(*) FROM practice_tenant_registry r" + where)
			.params(parameters)
			.query(Long.class)
			.single();
		parameters.put("limit", size);
		parameters.put("offset", page * size);
		var items = jdbcClient.sql("""
			SELECT %s,
				i.display_name AS administrator_name,
				i.email AS administrator_email,
				i.setup_status AS administrator_setup_status
			FROM practice_tenant_registry r
			LEFT JOIN practice_administrator_setup i ON i.practice_id = r.practice_id
			%s
			ORDER BY r.updated_at DESC, r.display_name, r.practice_id
			LIMIT :limit OFFSET :offset
			""".formatted(TENANT_COLUMNS, where))
			.params(parameters)
			.query(this::mapAdministrationView)
			.list();
		return new PracticeAdministrationPage(items, total, page, size);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PracticeAdministrationView> findAdministrationView(UUID practiceId) {
		enablePlatformOperationsContext();
		return jdbcClient.sql("""
			SELECT %s,
				i.display_name AS administrator_name,
				i.email AS administrator_email,
				i.setup_status AS administrator_setup_status
			FROM practice_tenant_registry r
			LEFT JOIN practice_administrator_setup i ON i.practice_id = r.practice_id
			WHERE r.practice_id = :practiceId
			""".formatted(TENANT_COLUMNS))
			.param("practiceId", practiceId)
			.query(this::mapAdministrationView)
			.optional();
	}

	@Override
	@Transactional
	public PracticeAdministrationView updateDisplayName(
		UUID practiceId,
		String displayName,
		long expectedVersion,
		UUID idempotencyKey,
		UUID actorUserId,
		OffsetDateTime now
	) {
		enablePlatformOperationsContext();
		var retry = resolveManagementRetry(practiceId, "DISPLAY_NAME_UPDATED", idempotencyKey);
		if (retry.isPresent()) return retry.get();
		var current = requireAdministrationView(practiceId);
		if (current.tenant().displayName().equals(displayName)) return current;
		var updated = jdbcClient.sql("""
			UPDATE practice_tenant_registry
			SET display_name = :displayName, updated_at = :now, version = version + 1
			WHERE practice_id = :practiceId AND version = :expectedVersion
			""")
			.param("displayName", displayName)
			.param("now", now)
			.param("practiceId", practiceId)
			.param("expectedVersion", expectedVersion)
			.update();
		if (updated != 1) throw stalePractice();
		appendAdministrationEvent(
			practiceId, "DISPLAY_NAME_UPDATED", actorUserId, null,
			current.tenant().displayName(), displayName, idempotencyKey, expectedVersion + 1, now
		);
		return requireAdministrationView(practiceId);
	}

	@Override
	@Transactional
	public PracticeAdministrationView changeServiceStatus(
		UUID practiceId,
		PracticeServiceStatus targetStatus,
		String reason,
		long expectedVersion,
		UUID idempotencyKey,
		UUID actorUserId,
		OffsetDateTime now
	) {
		enablePlatformOperationsContext();
		var eventType = targetStatus == PracticeServiceStatus.SUSPENDED
			? "PRACTICE_SUSPENDED" : "PRACTICE_REACTIVATED";
		var retry = resolveManagementRetry(practiceId, eventType, idempotencyKey);
		if (retry.isPresent()) return retry.get();
		var current = requireAdministrationView(practiceId);
		if (current.tenant().status() != PracticeProvisioningStatus.ACTIVE) {
			throw new PracticeAdministrationConflictException(
				"Only an active, successfully provisioned Practice can change service access."
			);
		}
		if (current.tenant().serviceStatus() == targetStatus) return current;
		var suspended = targetStatus == PracticeServiceStatus.SUSPENDED;
		var updated = jdbcClient.sql("""
			UPDATE practice_tenant_registry
			SET service_status = :serviceStatus,
				suspended_at = :suspendedAt,
				suspension_reason = :suspensionReason,
				suspended_by_user_id = :suspendedBy,
				updated_at = :now,
				version = version + 1
			WHERE practice_id = :practiceId AND version = :expectedVersion
			""")
			.param("serviceStatus", targetStatus.name())
			.param("suspendedAt", suspended ? now : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
			.param("suspensionReason", suspended ? reason : null, java.sql.Types.VARCHAR)
			.param("suspendedBy", suspended ? actorUserId : null, java.sql.Types.OTHER)
			.param("now", now)
			.param("practiceId", practiceId)
			.param("expectedVersion", expectedVersion)
			.update();
		if (updated != 1) throw stalePractice();
		appendAdministrationEvent(
			practiceId, eventType, actorUserId, reason,
			current.tenant().serviceStatus().name(), targetStatus.name(), idempotencyKey,
			expectedVersion + 1, now
		);
		return requireAdministrationView(practiceId);
	}

	private Optional<PracticeTenant> findForUpdate(String column, UUID value) {
		if (!column.equals("practice_id") && !column.equals("provisioning_idempotency_key")) {
			throw new IllegalArgumentException("Unsupported tenant lookup column.");
		}
		return jdbcClient.sql("SELECT " + TENANT_COLUMNS + " FROM practice_tenant_registry r WHERE r."
			+ column + " = :value FOR UPDATE")
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

	private void assertEligibleAdministrator(UUID practiceId, UUID userId) {
		var account = jdbcClient.sql("""
			SELECT account.role,
				account.enabled OR EXISTS (
					SELECT 1
					FROM practice_administrator_setup setup
					WHERE setup.practice_id = :practiceId
						AND setup.user_id = account.id
						AND setup.setup_status IN ('PASSWORD_NOT_ISSUED', 'TEMPORARY_PASSWORD_ISSUED')
				) AS eligible_state
			FROM platform_user_account account
			WHERE account.id = :userId
			""")
			.param("practiceId", practiceId)
			.param("userId", userId)
			.query((resultSet, rowNumber) -> new AdministratorEligibility(
				resultSet.getString("role"),
				resultSet.getBoolean("eligible_state")
			))
			.optional();
		if (account.isEmpty()
			|| !account.get().eligibleState()
			|| (!account.get().role().equals("CLINICIAN") && !account.get().role().equals("PRACTICE_STAFF"))) {
			throw new PracticeProvisioningConflictException(
				"The initial Practice administrator must be an enabled practice user or have a support-prepared account."
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

	private Optional<PracticeAdministrationView> resolveManagementRetry(
		UUID practiceId,
		String eventType,
		UUID idempotencyKey
	) {
		var existing = jdbcClient.sql("""
			SELECT practice_id, event_type
			FROM practice_administration_event
			WHERE idempotency_key = :idempotencyKey
			""")
			.param("idempotencyKey", idempotencyKey)
			.query((resultSet, rowNumber) -> new ManagementOperation(
				resultSet.getObject("practice_id", UUID.class), resultSet.getString("event_type")
			))
			.optional();
		if (existing.isEmpty()) return Optional.empty();
		if (!existing.get().practiceId().equals(practiceId) || !existing.get().eventType().equals(eventType)) {
			throw new PracticeAdministrationConflictException(
				"The idempotency key was already used for a different Practice management action."
			);
		}
		return Optional.of(requireAdministrationView(practiceId));
	}

	private void appendAdministrationEvent(
		UUID practiceId,
		String eventType,
		UUID actorUserId,
		String reason,
		String previousValue,
		String nextValue,
		UUID idempotencyKey,
		long resultingVersion,
		OffsetDateTime occurredAt
	) {
		jdbcClient.sql("""
			INSERT INTO practice_administration_event (
				id, practice_id, event_type, actor_user_id, reason,
				previous_value, next_value, idempotency_key, resulting_version, occurred_at
			) VALUES (
				:id, :practiceId, :eventType, :actorUserId, :reason,
				:previousValue, :nextValue, :idempotencyKey, :resultingVersion, :occurredAt
			)
			""")
			.param("id", UUID.randomUUID())
			.param("practiceId", practiceId)
			.param("eventType", eventType)
			.param("actorUserId", actorUserId)
			.param("reason", reason, java.sql.Types.VARCHAR)
			.param("previousValue", previousValue, java.sql.Types.VARCHAR)
			.param("nextValue", nextValue, java.sql.Types.VARCHAR)
			.param("idempotencyKey", idempotencyKey)
			.param("resultingVersion", resultingVersion)
			.param("occurredAt", occurredAt)
			.update();
	}

	private PracticeAdministrationView requireAdministrationView(UUID practiceId) {
		return findAdministrationView(practiceId)
			.orElseThrow(() -> new PracticeAdministrationConflictException("Practice not found."));
	}

	private PracticeAdministrationConflictException stalePractice() {
		return new PracticeAdministrationConflictException(
			"This Practice changed after it was loaded. Refresh it before trying again."
		);
	}

	private String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private PracticeTenant mapTenant(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PracticeTenant(
			resultSet.getObject("practice_id", UUID.class),
			resultSet.getString("practice_code"),
			resultSet.getString("display_name"),
			resultSet.getObject("initial_administrator_user_id", UUID.class),
			PracticeProvisioningStatus.valueOf(resultSet.getString("provisioning_status")),
			PracticeServiceStatus.valueOf(resultSet.getString("service_status")),
			resultSet.getString("database_name"),
			resultSet.getString("jdbc_url"),
			resultSet.getString("runtime_secret_reference"),
			resultSet.getString("migration_secret_reference"),
			resultSet.getString("schema_version"),
			resultSet.getInt("provisioning_attempts"),
			resultSet.getString("failure_code"),
			resultSet.getString("failure_message"),
			resultSet.getObject("suspended_at", OffsetDateTime.class),
			resultSet.getString("suspension_reason"),
			resultSet.getObject("suspended_by_user_id", UUID.class),
			resultSet.getObject("created_at", OffsetDateTime.class),
			resultSet.getObject("updated_at", OffsetDateTime.class),
			resultSet.getLong("version")
		);
	}

	private PracticeAdministrationView mapAdministrationView(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PracticeAdministrationView(
			mapTenant(resultSet, rowNumber),
			resultSet.getString("administrator_name"),
			resultSet.getString("administrator_email"),
			resultSet.getString("administrator_setup_status")
		);
	}

	private record AdministratorEligibility(String role, boolean eligibleState) {
	}

	private record ManagementOperation(UUID practiceId, String eventType) {
	}
}
