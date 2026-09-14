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
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.platformoperations.configuration.TenantProvisioningProperties;
import ph.epr.api.platformoperations.domain.InitialTenantSite;
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

		var firstSite = site("Makati Clinic");
		var secondSite = site("Quezon Clinic");
		var firstRoute = manager.provision(first, firstSite);
		var repeatedRoute = manager.provision(first, firstSite);
		var secondRoute = manager.provision(second, secondSite);

		assertThat(repeatedRoute).isEqualTo(firstRoute);
		assertThat(firstRoute.databaseName()).isNotEqualTo(secondRoute.databaseName());
		assertThat(firstRoute.schemaVersion()).isEqualTo("2");
		assertThat(secondRoute.schemaVersion()).isEqualTo("2");

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
		try (var connection = DriverManager.getConnection(
			firstRoute.jdbcUrl(), firstRuntime.username(), firstRuntime.password()
		); var statement = connection.prepareStatement("""
			SELECT practice_id, name, locality_psgc_code, time_zone FROM practice_site WHERE site_id = ?
			""")) {
			statement.setObject(1, firstSite.siteId());
			try (var resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getObject(1, UUID.class)).isEqualTo(first.practiceId());
				assertThat(resultSet.getString(2)).isEqualTo("Makati Clinic");
				assertThat(resultSet.getString(3)).isEqualTo("1380300000");
				assertThat(resultSet.getString(4)).isEqualTo("Asia/Manila");
			}
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

		assertThatThrownBy(() -> manager(new FakeTenantCredentialStore()).provision(tenant, site("Unsafe Clinic")))
			.hasMessage("A server-derived tenant role exists with unsafe privileges.");
	}

	private InitialTenantSite site(String name) {
		return new InitialTenantSite(
			UUID.randomUUID(), name, "Medical Arts Building", "+63 917 000 0000",
			"PH", "1300000000", "1300000000", "1380300000"
		);
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
			PracticeServiceStatus.ENABLED,
			null,
			null,
			null,
			null,
			null,
			1,
			null,
			null,
			null,
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
