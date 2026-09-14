package ph.epr.api.identitytenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import ph.epr.api.identitytenancy.PracticeProvisioningConflictException;
import ph.epr.api.identitytenancy.PracticeAdministrationConflictException;
import ph.epr.api.identitytenancy.PracticeProvisioningRequest;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;

@Testcontainers
class JdbcPracticeProvisioningRegistryTests {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-14T04:00:00Z");

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgres:17.11-alpine3.24")
	);

	private JdbcClient jdbcClient;
	private JdbcPracticeProvisioningRegistry registry;
	private TransactionTemplate transaction;
	private UUID requesterId;
	private UUID administratorId;

	@BeforeAll
	static void migrate() {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
		);
		jdbcClient = JdbcClient.create(dataSource);
		registry = new JdbcPracticeProvisioningRegistry(jdbcClient);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		jdbcClient.sql("TRUNCATE practice_administration_event, tenant_provisioning_event, local_tenant_database_credential, practice_role_assignment, practice_membership, practice_tenant_registry, platform_user_account CASCADE")
			.update();
		requesterId = insertUser("requester", "SUPERADMIN", true);
		administratorId = insertUser("administrator", "PRACTICE_STAFF", true);
	}

	@Test
	void claimsActivatesAndIdempotentlyReturnsTheSameTenant() {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "makati-clinic", "a".repeat(64));

		var first = inTransaction(() -> registry.claim(request, NOW));
		var concurrentRetry = inTransaction(() -> registry.claim(request, NOW.plusSeconds(1)));
		var route = new TenantDatabaseRoute(
			"epr_t_database", "jdbc:postgresql://server/epr_t_database", "runtime-ref", "migration-ref", "1"
		);
		var active = inTransaction(() -> registry.activate(
			request.practiceId(), request.idempotencyKey(), 1, requesterId, route, NOW.plusSeconds(2)
		));
		jdbcClient.sql("UPDATE platform_user_account SET enabled = FALSE WHERE id = :userId")
			.param("userId", request.initialAdministratorUserId())
			.update();
		var completedRetry = inTransaction(() -> registry.claim(request, NOW.plusSeconds(3)));

		assertThat(first.provisioningRequired()).isTrue();
		assertThat(concurrentRetry.provisioningRequired()).isFalse();
		assertThat(active.status()).isEqualTo(PracticeProvisioningStatus.ACTIVE);
		assertThat(active.isRoutable()).isTrue();
		assertThat(completedRetry.provisioningRequired()).isFalse();
		assertThat(jdbcClient.sql("SELECT count(*) FROM practice_membership WHERE practice_id = :practiceId")
			.param("practiceId", request.practiceId())
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(jdbcClient.sql("""
			SELECT practice_role
			FROM practice_role_assignment
			WHERE practice_id = :practiceId AND user_id = :userId
			""")
			.param("practiceId", request.practiceId())
			.param("userId", request.initialAdministratorUserId())
			.query(String.class)
			.list()).containsExactly("PRACTICE_ADMINISTRATOR");
		assertThat(jdbcClient.sql("SELECT event_type FROM tenant_provisioning_event ORDER BY occurred_at")
			.query(String.class)
			.list()).containsExactly("PROVISIONING_STARTED", "PROVISIONING_SUCCEEDED");
	}

	@Test
	void listsSearchesAndSafelyChangesPracticeServiceAccess() {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "santos-clinic", "9".repeat(64));
		inTransaction(() -> registry.claim(request, NOW));
		var route = new TenantDatabaseRoute(
			"epr_t_santos", "jdbc:postgresql://server/epr_t_santos", "runtime-ref", "migration-ref", "2"
		);
		var active = inTransaction(() -> registry.activate(
			request.practiceId(), request.idempotencyKey(), 1, requesterId, route, NOW.plusSeconds(1)
		));
		var suspensionKey = UUID.randomUUID();
		var suspended = inTransaction(() -> registry.changeServiceStatus(
			request.practiceId(), PracticeServiceStatus.SUSPENDED, "Practice requested temporary closure.",
			active.version(), suspensionKey, requesterId, NOW.plusSeconds(2)
		));
		var retry = inTransaction(() -> registry.changeServiceStatus(
			request.practiceId(), PracticeServiceStatus.SUSPENDED, "Practice requested temporary closure.",
			active.version(), suspensionKey, requesterId, NOW.plusSeconds(3)
		));

		assertThat(suspended.tenant().serviceStatus()).isEqualTo(PracticeServiceStatus.SUSPENDED);
		assertThat(suspended.tenant().isRoutable()).isFalse();
		assertThat(retry.tenant().version()).isEqualTo(suspended.tenant().version());
		assertThat(registry.list("santos", null, PracticeServiceStatus.SUSPENDED, 0, 25).items())
			.extracting(item -> item.tenant().practiceId())
			.containsExactly(request.practiceId());
		assertThat(jdbcClient.sql("SELECT count(*) FROM practice_administration_event")
			.query(Integer.class).single()).isEqualTo(1);

		assertThatThrownBy(() -> inTransaction(() -> registry.updateDisplayName(
			request.practiceId(), "Santos Family Practice", active.version(), UUID.randomUUID(),
			requesterId, NOW.plusSeconds(4)
		))).isInstanceOf(PracticeAdministrationConflictException.class)
			.hasMessageContaining("changed after it was loaded");
	}

	@Test
	void concurrentClaimsPerformProvisioningOnlyOnce() throws Exception {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "concurrent-clinic", "8".repeat(64));
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				ready.countDown();
				start.await();
				return inTransaction(() -> registry.claim(request, NOW));
			});
			var second = executor.submit(() -> {
				ready.countDown();
				start.await();
				return inTransaction(() -> registry.claim(request, NOW));
			});
			ready.await();
			start.countDown();

			assertThat(java.util.List.of(
				first.get().provisioningRequired(),
				second.get().provisioningRequired()
			)).containsExactlyInAnyOrder(true, false);
		}
		assertThat(jdbcClient.sql("SELECT count(*) FROM practice_tenant_registry WHERE practice_id = :practiceId")
			.param("practiceId", request.practiceId())
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void quarantinesAndRetriesWithTheOriginalIdempotencyKey() {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "retry-clinic", "b".repeat(64));
		inTransaction(() -> registry.claim(request, NOW));
		var quarantined = inTransaction(() -> registry.quarantine(
			request.practiceId(),
			request.idempotencyKey(),
			1,
			requesterId,
			"TENANT_SCHEMA_MIGRATION_FAILED",
			"Tenant schema migration failed.",
			NOW.plusSeconds(1)
		));

		var retried = inTransaction(() -> registry.claim(request, NOW.plusSeconds(2)));

		assertThat(quarantined.status()).isEqualTo(PracticeProvisioningStatus.QUARANTINED);
		assertThat(quarantined.isRoutable()).isFalse();
		assertThat(retried.provisioningRequired()).isTrue();
		assertThat(retried.tenant().provisioningAttempts()).isEqualTo(2);
		assertThat(retried.tenant().failureCode()).isNull();
	}

	@Test
	void recoversAnExpiredProvisioningLeaseButDoesNotStealAnActiveLease() {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "lease-clinic", "c".repeat(64));
		inTransaction(() -> registry.claim(request, NOW));

		var activeLease = inTransaction(() -> registry.claim(request, NOW.plusMinutes(14)));
		var recovered = inTransaction(() -> registry.claim(request, NOW.plusMinutes(16)));

		assertThat(activeLease.provisioningRequired()).isFalse();
		assertThat(recovered.provisioningRequired()).isTrue();
		assertThat(recovered.tenant().provisioningAttempts()).isEqualTo(2);
	}

	@Test
	void preventsAStaleWorkerFromQuarantiningANewerAttempt() {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "stale-worker-clinic", "9".repeat(64));
		inTransaction(() -> registry.claim(request, NOW));
		inTransaction(() -> registry.quarantine(
			request.practiceId(), request.idempotencyKey(), 1, requesterId,
			"FIRST_ATTEMPT_FAILED", "First attempt failed.", NOW.plusSeconds(1)
		));
		var retry = inTransaction(() -> registry.claim(request, NOW.plusSeconds(2)));

		var afterStaleFailure = inTransaction(() -> registry.quarantine(
			request.practiceId(), request.idempotencyKey(), 1, requesterId,
			"STALE_WORKER_FAILED", "Stale worker failed.", NOW.plusSeconds(3)
		));

		assertThat(retry.tenant().provisioningAttempts()).isEqualTo(2);
		assertThat(afterStaleFailure.status()).isEqualTo(PracticeProvisioningStatus.PROVISIONING);
		assertThat(afterStaleFailure.failureCode()).isNull();
		assertThat(jdbcClient.sql("SELECT count(*) FROM tenant_provisioning_event WHERE detail_code = 'STALE_WORKER_FAILED'")
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void rejectsIdempotencyReuseWithDifferentRequestData() {
		var practiceId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID();
		inTransaction(() -> registry.claim(
			request(practiceId, idempotencyKey, "original-clinic", "d".repeat(64)), NOW
		));

		assertThatThrownBy(() -> inTransaction(() -> registry.claim(
			request(practiceId, idempotencyKey, "changed-clinic", "e".repeat(64)), NOW.plusSeconds(1)
		)))
			.isInstanceOf(PracticeProvisioningConflictException.class)
			.hasMessageContaining("idempotency key");
	}

	@Test
	void rejectsDisabledInitialAdministrator() {
		var disabledUserId = insertUser("disabled", "PRACTICE_STAFF", false);
		var request = new PracticeProvisioningRequest(
			UUID.randomUUID(),
			"disabled-admin-clinic",
			"Disabled Admin Clinic",
			disabledUserId,
			UUID.randomUUID(),
			requesterId,
			"f".repeat(64)
		);

		assertThatThrownBy(() -> inTransaction(() -> registry.claim(request, NOW)))
			.isInstanceOf(PracticeProvisioningConflictException.class)
			.hasMessageContaining("enabled practice user");
	}

	@Test
	void acceptsAClinicianAsTheInitialAdministrator() {
		var clinicianId = insertUser("clinician-administrator", "CLINICIAN", true);
		var request = new PracticeProvisioningRequest(
			UUID.randomUUID(),
			"clinician-admin-clinic",
			"Clinician Admin Clinic",
			clinicianId,
			UUID.randomUUID(),
			requesterId,
			"5".repeat(64)
		);

		assertThat(inTransaction(() -> registry.claim(request, NOW)).provisioningRequired()).isTrue();
	}

	@Test
	void acceptsALegacyPasswordNotIssuedAccountPreparedForThisPracticeOnly() {
		var practiceId = UUID.randomUUID();
		var invitedUserId = insertUser("prepared-invitee", "PRACTICE_STAFF", false);
		jdbcClient.sql("""
			INSERT INTO practice_administrator_setup (
				id, practice_id, user_id, email, display_name, setup_status,
				requested_by_user_id, created_at, updated_at
			) VALUES (
				:id, :practiceId, :userId, 'prepared@example.test', 'Prepared Account', 'PASSWORD_NOT_ISSUED',
				:requesterId, :now, :now
			)
			""")
			.param("id", UUID.randomUUID())
			.param("practiceId", practiceId)
			.param("userId", invitedUserId)
			.param("requesterId", requesterId)
			.param("now", NOW)
			.update();
		var request = new PracticeProvisioningRequest(
			practiceId,
			"prepared-invitee-clinic",
			"Prepared Invitee Clinic",
			invitedUserId,
			UUID.randomUUID(),
			requesterId,
			"3".repeat(64)
		);

		assertThat(inTransaction(() -> registry.claim(request, NOW)).provisioningRequired()).isTrue();
	}

	@Test
	void neverConvertsProviderAuthorityIntoPracticeAdministration() {
		var providerUserId = insertUser("provider", "PROVIDER_SUPPORT", true);
		var request = new PracticeProvisioningRequest(
			UUID.randomUUID(),
			"provider-admin-clinic",
			"Provider Admin Clinic",
			providerUserId,
			UUID.randomUUID(),
			requesterId,
			"6".repeat(64)
		);

		assertThatThrownBy(() -> inTransaction(() -> registry.claim(request, NOW)))
			.isInstanceOf(PracticeProvisioningConflictException.class)
			.hasMessageContaining("enabled practice user");
	}

	@Test
	void neverConvertsSuperadminAuthorityIntoPracticeAdministration() {
		var request = new PracticeProvisioningRequest(
			UUID.randomUUID(),
			"superadmin-admin-clinic",
			"Superadmin Admin Clinic",
			requesterId,
			UUID.randomUUID(),
			requesterId,
			"4".repeat(64)
		);

		assertThatThrownBy(() -> inTransaction(() -> registry.claim(request, NOW)))
			.isInstanceOf(PracticeProvisioningConflictException.class)
			.hasMessageContaining("enabled practice user");
	}

	@Test
	void rowLevelSecurityFailsClosedWithoutAnExplicitTrustedContext() {
		var request = request(UUID.randomUUID(), UUID.randomUUID(), "rls-clinic", "7".repeat(64));
		inTransaction(() -> registry.claim(request, NOW));
		var applicationUsername = "registry_test_app";
		var applicationPassword = "RegistryTestPassword_00000000000000000000";
		jdbcClient.sql("""
			DO $block$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'registry_test_app') THEN
					CREATE ROLE registry_test_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
						NOBYPASSRLS PASSWORD 'RegistryTestPassword_00000000000000000000';
				END IF;
			END
			$block$;
			""").update();
		jdbcClient.sql("GRANT CONNECT ON DATABASE \"" + POSTGRES.getDatabaseName() + "\" TO " + applicationUsername)
			.update();
		jdbcClient.sql("GRANT USAGE ON SCHEMA public TO " + applicationUsername).update();
		jdbcClient.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + applicationUsername)
			.update();
		var applicationDataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), applicationUsername, applicationPassword
		);
		var applicationJdbc = JdbcClient.create(applicationDataSource);

		assertThat(applicationJdbc.sql("SELECT count(*) FROM practice_tenant_registry")
			.query(Integer.class)
			.single()).isZero();

		var applicationRegistry = new JdbcPracticeProvisioningRegistry(applicationJdbc);
		var applicationTransaction = new TransactionTemplate(new DataSourceTransactionManager(applicationDataSource));
		var resolved = applicationTransaction.execute(status -> applicationRegistry.findByPracticeId(request.practiceId()));
		assertThat(resolved).isPresent();
	}

	private PracticeProvisioningRequest request(
		UUID practiceId,
		UUID idempotencyKey,
		String code,
		String fingerprint
	) {
		return new PracticeProvisioningRequest(
			practiceId,
			code,
			"Test Practice",
			administratorId,
			idempotencyKey,
			requesterId,
			fingerprint
		);
	}

	private UUID insertUser(String username, String role, boolean enabled) {
		var id = UUID.randomUUID();
		jdbcClient.sql("""
			INSERT INTO platform_user_account (
				id, username, display_name, password_hash, role, enabled, created_at, updated_at
			) VALUES (
				:id, :username, :displayName, 'hash', :role, :enabled, :now, :now
			)
			""")
			.param("id", id)
			.param("username", username)
			.param("displayName", username)
			.param("role", role)
			.param("enabled", enabled)
			.param("now", NOW)
			.update();
		return id;
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return transaction.execute(status -> work.get());
	}
}
