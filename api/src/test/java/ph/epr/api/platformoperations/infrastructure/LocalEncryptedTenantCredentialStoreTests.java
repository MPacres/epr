package ph.epr.api.platformoperations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import ph.epr.api.platformoperations.application.TenantProvisioningException;
import ph.epr.api.platformoperations.configuration.TenantProvisioningProperties;

@Testcontainers
class LocalEncryptedTenantCredentialStoreTests {

	private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-14T04:00:00Z");

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgres:17.11-alpine3.24")
	);

	private JdbcClient jdbcClient;
	private Clock clock;
	private UUID practiceId;

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
		clock = Clock.fixed(Instant.parse("2026-09-14T04:00:00Z"), ZoneOffset.UTC);
		jdbcClient.sql("TRUNCATE tenant_provisioning_event, local_tenant_database_credential, practice_membership, practice_tenant_registry, platform_user_account CASCADE")
			.update();
		var userId = UUID.randomUUID();
		practiceId = UUID.randomUUID();
		jdbcClient.sql("""
			INSERT INTO platform_user_account (
				id, username, display_name, password_hash, role, enabled, created_at, updated_at
			) VALUES (:id, 'superadmin', 'Superadmin', 'hash', 'SUPERADMIN', TRUE, :now, :now)
			""")
			.param("id", userId)
			.param("now", NOW)
			.update();
		jdbcClient.sql("""
			INSERT INTO practice_tenant_registry (
				practice_id, practice_code, display_name, initial_administrator_user_id,
				provisioning_status, provisioning_idempotency_key, request_fingerprint,
				requested_by_user_id, provisioning_lease_expires_at,
				provisioning_attempts, created_at, updated_at, version
			) VALUES (
				:practiceId, 'credential-test', 'Credential Test', :userId,
				'PROVISIONING', :idempotencyKey, :fingerprint,
				:userId, :lease, 1, :now, :now, 0
			)
			""")
			.param("practiceId", practiceId)
			.param("userId", userId)
			.param("idempotencyKey", UUID.randomUUID())
			.param("fingerprint", "a".repeat(64))
			.param("lease", NOW.plusMinutes(15))
			.param("now", NOW)
			.update();
	}

	@Test
	void encryptsCredentialsAndReturnsTheSameSecretAcrossStoreInstances() {
		var firstStore = new LocalEncryptedTenantCredentialStore(jdbcClient, properties(KEY), clock);
		var first = firstStore.getOrCreate(
			practiceId, TenantCredentialStore.CredentialPurpose.RUNTIME, "epr_runtime_test"
		);
		var secondStore = new LocalEncryptedTenantCredentialStore(jdbcClient, properties(KEY), clock);
		var repeated = secondStore.getOrCreate(
			practiceId, TenantCredentialStore.CredentialPurpose.RUNTIME, "epr_runtime_test"
		);
		var encrypted = jdbcClient.sql("""
			SELECT encrypted_password FROM local_tenant_database_credential
			WHERE practice_id = :practiceId
			""")
			.param("practiceId", practiceId)
			.query(byte[].class)
			.single();

		assertThat(repeated).isEqualTo(first);
		assertThat(encrypted).isNotEqualTo(first.password().getBytes(StandardCharsets.UTF_8));
		assertThat(encrypted).hasSizeGreaterThan(first.password().length());
	}

	@Test
	void failsClosedWhenTheCredentialEncryptionKeyChanges() {
		var store = new LocalEncryptedTenantCredentialStore(jdbcClient, properties(KEY), clock);
		store.getOrCreate(practiceId, TenantCredentialStore.CredentialPurpose.RUNTIME, "epr_runtime_test");
		var differentKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
		var wrongStore = new LocalEncryptedTenantCredentialStore(jdbcClient, properties(differentKey), clock);

		assertThatThrownBy(() -> wrongStore.getOrCreate(
			practiceId, TenantCredentialStore.CredentialPurpose.RUNTIME, "epr_runtime_test"
		))
			.isInstanceOf(TenantProvisioningException.class)
			.hasMessage("Tenant credentials could not be decrypted.");
	}

	private TenantProvisioningProperties properties(String key) {
		return new TenantProvisioningProperties(
			true,
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword(),
			"jdbc:postgresql://localhost/{database}",
			key
		);
	}
}
