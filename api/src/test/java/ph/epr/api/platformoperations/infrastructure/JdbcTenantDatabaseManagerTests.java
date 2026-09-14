package ph.epr.api.platformoperations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.platformoperations.configuration.TenantProvisioningProperties;
import ph.epr.api.platformoperations.domain.TenantIdentifierPolicy;

@Testcontainers
class JdbcTenantDatabaseManagerTests {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgres:17.11-alpine3.24")
	);

	@Test
	void provisionsIsolatedTenantDatabasesAndSafelyRepeatsTheWorkflow() throws SQLException {
		var credentials = new FakeTenantCredentialStore();
		var manager = manager(credentials);
		var first = tenant("makati-family-clinic", "Makati Family Clinic");
		var second = tenant("quezon-primary-care", "Quezon Primary Care");

		var firstRoute = manager.provision(first);
		var repeatedRoute = manager.provision(first);
		var secondRoute = manager.provision(second);

		assertThat(repeatedRoute).isEqualTo(firstRoute);
		assertThat(firstRoute.databaseName()).isNotEqualTo(secondRoute.databaseName());
		assertThat(firstRoute.schemaVersion()).isEqualTo("1");
		assertThat(secondRoute.schemaVersion()).isEqualTo("1");

		var firstRuntime = credentials.credential(first.practiceId(), TenantCredentialStore.CredentialPurpose.RUNTIME);
		try (var connection = DriverManager.getConnection(
			firstRoute.jdbcUrl(), firstRuntime.username(), firstRuntime.password()
		); var statement = connection.prepareStatement("""
			SELECT practice_id, practice_code FROM tenant_practice
			"""); var resultSet = statement.executeQuery()) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getObject(1, UUID.class)).isEqualTo(first.practiceId());
			assertThat(resultSet.getString(2)).isEqualTo(first.code());
			assertThat(resultSet.next()).isFalse();
		}
		assertThatThrownBy(() -> {
			try (var connection = DriverManager.getConnection(
				firstRoute.jdbcUrl(), firstRuntime.username(), firstRuntime.password()
			); var statement = connection.createStatement()) {
				statement.executeUpdate("DELETE FROM tenant_practice");
			}
		})
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("permission denied for table tenant_practice");

		assertThatThrownBy(() -> DriverManager.getConnection(
			secondRoute.jdbcUrl(), firstRuntime.username(), firstRuntime.password()
		))
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("permission denied for database");
		assertThatThrownBy(() -> DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), firstRuntime.username(), firstRuntime.password()
		))
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("permission denied for database");
	}

	@Test
	void rejectsAExistingDerivedRoleWithUnsafePrivileges() throws SQLException {
		var tenant = tenant("unsafe-role-clinic", "Unsafe Role Clinic");
		var role = TenantIdentifierPolicy.runtimeRole(tenant.practiceId());
		try (var connection = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
		); var statement = connection.createStatement()) {
			statement.execute("CREATE ROLE \"" + role + "\" LOGIN CREATEDB PASSWORD 'UnsafeRolePassword000000000000000000000000'");
		}

		assertThatThrownBy(() -> manager(new FakeTenantCredentialStore()).provision(tenant))
			.hasMessage("A server-derived tenant role exists with unsafe privileges.");
	}

	private JdbcTenantDatabaseManager manager(TenantCredentialStore credentialStore) {
		var tenantUrlTemplate = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
			+ POSTGRES.getMappedPort(5432) + "/{database}";
		return new JdbcTenantDatabaseManager(
			new TenantProvisioningProperties(
				true,
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword(),
				tenantUrlTemplate,
				"unused-by-this-test"
			),
			credentialStore,
			Clock.systemUTC()
		);
	}

	private PracticeTenant tenant(String code, String displayName) {
		var now = OffsetDateTime.now(ZoneOffset.UTC);
		return new PracticeTenant(
			UUID.randomUUID(),
			code,
			displayName,
			UUID.randomUUID(),
			PracticeProvisioningStatus.PROVISIONING,
			null,
			null,
			null,
			null,
			null,
			1,
			null,
			null,
			now,
			now,
			0
		);
	}

	private static final class FakeTenantCredentialStore implements TenantCredentialStore {
		private final Map<String, TenantDatabaseCredential> credentials = new HashMap<>();

		@Override
		public TenantDatabaseCredential getOrCreate(UUID practiceId, CredentialPurpose purpose, String username) {
			return credentials.computeIfAbsent(practiceId + ":" + purpose, ignored -> new TenantDatabaseCredential(
				"test-secret://" + practiceId + "/" + purpose.name().toLowerCase(),
				username,
				"SafeTenantPassword_" + practiceId.toString().replace("-", "")
			));
		}

		TenantDatabaseCredential credential(UUID practiceId, CredentialPurpose purpose) {
			return credentials.get(practiceId + ":" + purpose);
		}
	}
}
