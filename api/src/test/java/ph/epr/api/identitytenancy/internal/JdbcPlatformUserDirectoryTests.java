package ph.epr.api.identitytenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import ph.epr.api.identitytenancy.PracticeAdministratorSetup.SetupStatus;
import ph.epr.api.identitytenancy.PracticeProvisioningConflictException;

@Testcontainers
class JdbcPlatformUserDirectoryTests {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-14T06:00:00Z");

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgres:17.11-alpine3.24")
	);

	private JdbcClient jdbcClient;
	private JdbcPlatformUserDirectory users;
	private PasswordEncoder encoder;
	private TransactionTemplate transaction;
	private UUID requesterId;

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
		encoder = new BCryptPasswordEncoder(4);
		users = new JdbcPlatformUserDirectory(jdbcClient, encoder);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		jdbcClient.sql("TRUNCATE practice_administrator_setup, practice_administration_event, tenant_provisioning_event, local_tenant_database_credential, practice_role_assignment, practice_membership, practice_tenant_registry, platform_user_account CASCADE")
			.update();
		requesterId = insertUser("superadmin@example.test", "Local Superadmin", "SUPERADMIN", true);
	}

	@Test
	void preparesOneEnabledTemporaryPasswordIdentityWithoutRedisclosingTheSecret() {
		var practiceId = UUID.randomUUID();

		var first = inTransaction(() -> users.preparePracticeAdministrator(
			practiceId, "Alex Reyes", "Alex.Reyes@Example.Test", requesterId, NOW
		));
		var repeated = inTransaction(() -> users.preparePracticeAdministrator(
			practiceId, "Alex Reyes", "alex.reyes@example.test", requesterId, NOW.plusSeconds(1)
		));

		assertThat(first.userId()).isEqualTo(repeated.userId());
		assertThat(first.setupStatus()).isEqualTo(SetupStatus.TEMPORARY_PASSWORD_ISSUED);
		assertThat(first.temporaryPassword())
			.hasSize(20)
			.matches(".*[A-Z].*")
			.matches(".*[a-z].*")
			.matches(".*[0-9].*")
			.matches(".*[^A-Za-z0-9].*");
		assertThat(repeated.temporaryPassword()).isNull();
		assertThat(jdbcClient.sql("SELECT enabled AND password_reset_required FROM platform_user_account WHERE id = :id")
			.param("id", first.userId()).query(Boolean.class).single()).isTrue();
		assertThat(jdbcClient.sql("SELECT count(*) FROM practice_administrator_setup WHERE practice_id = :practiceId")
			.param("practiceId", practiceId).query(Integer.class).single()).isOne();
		assertThat(jdbcClient.sql("SELECT password_hash FROM platform_user_account WHERE id = :id")
			.param("id", first.userId()).query(String.class).single())
			.doesNotContain(first.temporaryPassword());
	}

	@Test
	void linksAnExistingEligiblePracticeAccountWithoutCreatingCredentials() {
		var existingId = insertUser("alex.reyes@example.test", "Alex Reyes", "CLINICIAN", true);

		var setup = inTransaction(() -> users.preparePracticeAdministrator(
			UUID.randomUUID(), "Alex Reyes", "alex.reyes@example.test", requesterId, NOW
		));

		assertThat(setup.userId()).isEqualTo(existingId);
		assertThat(setup.setupStatus()).isEqualTo(SetupStatus.EXISTING_ACCOUNT);
		assertThat(setup.temporaryPassword()).isNull();
	}

	@Test
	void issuesANewTemporaryPasswordForALegacySetupAndCompletesTheRequiredChange() {
		var practiceId = UUID.randomUUID();
		var accountId = insertUser("alex.reyes@example.test", "Alex Reyes", "PRACTICE_STAFF", false);
		insertSetup(practiceId, accountId, SetupStatus.PASSWORD_NOT_ISSUED);
		insertPractice(practiceId, accountId, 0);
		var issueKey = UUID.randomUUID();

		var issue = inTransaction(() -> users.issueTemporaryPassword(
			practiceId, 0, issueKey, requesterId, "Support staff is completing personal setup.", NOW.plusMinutes(1)
		));
		var repeated = inTransaction(() -> users.issueTemporaryPassword(
			practiceId, 0, issueKey, requesterId, "Support staff is completing personal setup.", NOW.plusMinutes(1)
		));

		assertThat(issue.newlyIssued()).isTrue();
		assertThat(issue.temporaryPassword()).hasSize(20);
		assertThat(repeated).isEqualTo(new ph.epr.api.identitytenancy.TemporaryPasswordIssue(null, false));
		assertThat(users.findByUsername("alex.reyes@example.test").orElseThrow().passwordResetRequired()).isTrue();
		assertThat(jdbcClient.sql("SELECT version FROM practice_tenant_registry WHERE practice_id = :practiceId")
			.param("practiceId", practiceId).query(Long.class).single()).isOne();

		var completed = inTransaction(() -> users.completeRequiredPasswordChange(
			accountId, "A-New-Private-Password9!", UUID.randomUUID(), NOW.plusMinutes(2)
		));
		assertThat(completed.passwordResetRequired()).isFalse();
		assertThat(encoder.matches("A-New-Private-Password9!", completed.passwordHash())).isTrue();
		assertThat(jdbcClient.sql("SELECT setup_status FROM practice_administrator_setup WHERE practice_id = :practiceId")
			.param("practiceId", practiceId).query(String.class).single()).isEqualTo("PASSWORD_SET");
	}

	@Test
	void rejectsKeepingTheTemporaryPassword() {
		var practiceId = UUID.randomUUID();
		var setup = inTransaction(() -> users.preparePracticeAdministrator(
			practiceId, "Alex Reyes", "alex.reyes@example.test", requesterId, NOW
		));
		insertPractice(practiceId, setup.userId(), 0);

		assertThatThrownBy(() -> inTransaction(() -> users.completeRequiredPasswordChange(
			setup.userId(), setup.temporaryPassword(), UUID.randomUUID(), NOW.plusMinutes(1)
		)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("different");
	}

	@Test
	void refusesToConvertProviderAuthorityIntoPracticeMembership() {
		insertUser("support@example.test", "Support Agent", "PROVIDER_SUPPORT", true);

		assertThatThrownBy(() -> inTransaction(() -> users.preparePracticeAdministrator(
			UUID.randomUUID(), "Support Agent", "support@example.test", requesterId, NOW
		)))
			.isInstanceOf(PracticeProvisioningConflictException.class)
			.hasMessageContaining("cannot receive Practice administration");
	}

	@Test
	void requiresIdentityReviewWhenAnEmailHasAnotherName() {
		insertUser("alex.reyes@example.test", "Alex Reyes", "PRACTICE_STAFF", true);

		assertThatThrownBy(() -> inTransaction(() -> users.preparePracticeAdministrator(
			UUID.randomUUID(), "Another Person", "alex.reyes@example.test", requesterId, NOW
		)))
			.isInstanceOf(PracticeProvisioningConflictException.class)
			.hasMessageContaining("Verify the identity");
	}

	private UUID insertUser(String username, String displayName, String role, boolean enabled) {
		var id = UUID.randomUUID();
		jdbcClient.sql("""
			INSERT INTO platform_user_account (
				id, username, display_name, password_hash, role, enabled, created_at, updated_at
			) VALUES (:id, :username, :displayName, 'hash', :role, :enabled, :now, :now)
			""")
			.param("id", id)
			.param("username", username)
			.param("displayName", displayName)
			.param("role", role)
			.param("enabled", enabled)
			.param("now", NOW)
			.update();
		return id;
	}

	private void insertSetup(UUID practiceId, UUID userId, SetupStatus status) {
		jdbcClient.sql("""
			INSERT INTO practice_administrator_setup (
				id, practice_id, user_id, email, display_name, setup_status,
				requested_by_user_id, created_at, updated_at
			) VALUES (
				:id, :practiceId, :userId, 'alex.reyes@example.test', 'Alex Reyes', :status,
				:requesterId, :now, :now
			)
			""")
			.param("id", UUID.randomUUID())
			.param("practiceId", practiceId)
			.param("userId", userId)
			.param("status", status.name())
			.param("requesterId", requesterId)
			.param("now", NOW)
			.update();
	}

	private void insertPractice(UUID practiceId, UUID administratorId, long version) {
		jdbcClient.sql("""
			INSERT INTO practice_tenant_registry (
				practice_id, practice_code, display_name, initial_administrator_user_id,
				provisioning_status, provisioning_idempotency_key, request_fingerprint,
				requested_by_user_id, database_name, jdbc_url, runtime_secret_reference,
				migration_secret_reference, schema_version, created_at, updated_at, version
			) VALUES (
				:practiceId, :code, 'Alex Clinic', :administratorId,
				'ACTIVE', :idempotencyKey, :fingerprint,
				:requesterId, 'tenant_database', 'jdbc:postgresql://localhost/tenant_database',
				'runtime', 'migration', '2', :now, :now, :version
			)
			""")
			.param("practiceId", practiceId)
			.param("code", "clinic-" + practiceId.toString().substring(0, 8))
			.param("administratorId", administratorId)
			.param("idempotencyKey", UUID.randomUUID())
			.param("fingerprint", "a".repeat(64))
			.param("requesterId", requesterId)
			.param("now", NOW)
			.param("version", version)
			.update();
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return transaction.execute(status -> work.get());
	}
}
