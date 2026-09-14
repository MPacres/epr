package ph.epr.api.platformoperations.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;
import ph.epr.api.platformoperations.application.TenantDatabaseManager;
import ph.epr.api.platformoperations.application.TenantProvisioningException;
import ph.epr.api.platformoperations.configuration.TenantProvisioningProperties;
import ph.epr.api.platformoperations.domain.TenantIdentifierPolicy;

@Component
@Profile("local")
@ConditionalOnProperty(name = "epr.tenant-provisioning.enabled", havingValue = "true")
class JdbcTenantDatabaseManager implements TenantDatabaseManager {

	private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

	private final TenantProvisioningProperties properties;
	private final TenantCredentialStore credentialStore;
	private final Clock clock;

	JdbcTenantDatabaseManager(
		TenantProvisioningProperties properties,
		TenantCredentialStore credentialStore,
		Clock clock
	) {
		this.properties = validate(properties);
		this.credentialStore = credentialStore;
		this.clock = clock;
	}

	@Override
	public TenantDatabaseRoute provision(PracticeTenant tenant) {
		if (tenant.status() != PracticeProvisioningStatus.PROVISIONING
			|| tenant.isRoutable()) {
			throw new IllegalArgumentException("Only a non-routable provisioning claim can create a tenant database.");
		}
		var databaseName = TenantIdentifierPolicy.databaseName(tenant.practiceId());
		var runtime = credentialStore.getOrCreate(
			tenant.practiceId(),
			TenantCredentialStore.CredentialPurpose.RUNTIME,
			TenantIdentifierPolicy.runtimeRole(tenant.practiceId())
		);
		var migration = credentialStore.getOrCreate(
			tenant.practiceId(),
			TenantCredentialStore.CredentialPurpose.MIGRATION,
			TenantIdentifierPolicy.migrationRole(tenant.practiceId())
		);
		var tenantJdbcUrl = properties.tenantJdbcUrlTemplate().replace("{database}", databaseName);

		try {
			hardenAdministrativeDatabaseBoundary();
			createOrVerifyRole(migration);
			createOrVerifyRole(runtime);
			createOrVerifyDatabase(databaseName, migration.username(), runtime.username());
			prepareTenantPrivileges(tenantJdbcUrl, migration, runtime.username());
			var schemaVersion = migrate(tenantJdbcUrl, migration);
			initializeTenantIdentity(tenantJdbcUrl, migration, runtime.username(), tenant);
			verifyRuntimeRoute(tenantJdbcUrl, runtime, tenant, databaseName);
			return new TenantDatabaseRoute(
				databaseName,
				tenantJdbcUrl,
				runtime.secretReference(),
				migration.secretReference(),
				schemaVersion
			);
		}
		catch (SQLException exception) {
			throw new TenantProvisioningException(
				"TENANT_DATABASE_OPERATION_FAILED",
				"Tenant database creation or verification failed.",
				exception
			);
		}
	}

	private void hardenAdministrativeDatabaseBoundary() throws SQLException {
		try (var connection = adminConnection(); var statement = connection.createStatement()) {
			try (var resultSet = statement.executeQuery("SELECT current_database()")) {
				resultSet.next();
				execute(
					connection,
					"REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE "
						+ identifier(resultSet.getString(1)) + " FROM PUBLIC"
				);
			}
		}
	}

	private void createOrVerifyRole(TenantDatabaseCredential credential) throws SQLException {
		assertIdentifier(credential.username());
		try (var connection = adminConnection()) {
			var existing = roleSecurity(connection, credential.username());
			if (existing == null) {
				execute(connection, "CREATE ROLE " + identifier(credential.username())
					+ " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD "
					+ literal(credential.password()));
			}
			else if (existing.superuser || existing.createDatabase || existing.createRole || existing.bypassRls
				|| hasGrantedRoles(connection, credential.username())) {
				throw new TenantProvisioningException(
					"UNSAFE_EXISTING_TENANT_ROLE",
					"A server-derived tenant role exists with unsafe privileges."
				);
			}
			execute(connection, "ALTER ROLE " + identifier(credential.username())
				+ " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD "
				+ literal(credential.password()));
			execute(connection, "ALTER ROLE " + identifier(credential.username()) + " SET row_security = on");
		}
	}

	private void createOrVerifyDatabase(String databaseName, String migrationRole, String runtimeRole) throws SQLException {
		assertIdentifier(databaseName);
		try (var connection = adminConnection()) {
			var owner = databaseOwner(connection, databaseName);
			if (owner == null) {
				execute(connection, "CREATE DATABASE " + identifier(databaseName)
					+ " OWNER " + identifier(migrationRole) + " ENCODING 'UTF8' TEMPLATE template0");
			}
			else if (!owner.equals(migrationRole)) {
				throw new TenantProvisioningException(
					"TENANT_DATABASE_OWNER_MISMATCH",
					"A server-derived tenant database exists under an unexpected owner."
				);
			}
			execute(connection, "REVOKE ALL ON DATABASE " + identifier(databaseName) + " FROM PUBLIC");
			execute(connection, "GRANT CONNECT ON DATABASE " + identifier(databaseName)
				+ " TO " + identifier(migrationRole));
			execute(connection, "GRANT CONNECT ON DATABASE " + identifier(databaseName)
				+ " TO " + identifier(runtimeRole));
		}
	}

	private void prepareTenantPrivileges(
		String tenantJdbcUrl,
		TenantDatabaseCredential migration,
		String runtimeRole
	) throws SQLException {
		try (var connection = DriverManager.getConnection(tenantJdbcUrl, migration.username(), migration.password())) {
			execute(connection, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
			execute(connection, "GRANT USAGE, CREATE ON SCHEMA public TO " + identifier(migration.username()));
			execute(connection, "GRANT USAGE ON SCHEMA public TO " + identifier(runtimeRole));
			execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE " + identifier(migration.username())
				+ " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + identifier(runtimeRole));
			execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE " + identifier(migration.username())
				+ " IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + identifier(runtimeRole));
		}
	}

	private String migrate(String tenantJdbcUrl, TenantDatabaseCredential migration) {
		try {
			var flyway = Flyway.configure()
				.dataSource(tenantJdbcUrl, migration.username(), migration.password())
				.locations("classpath:db/tenant-migration")
				.validateMigrationNaming(true)
				.load();
			flyway.migrate();
			var current = flyway.info().current();
			return current == null ? "0" : current.getVersion().toString();
		}
		catch (RuntimeException exception) {
			throw new TenantProvisioningException(
				"TENANT_SCHEMA_MIGRATION_FAILED",
				"Tenant schema migration failed.",
				exception
			);
		}
	}

	private void initializeTenantIdentity(
		String tenantJdbcUrl,
		TenantDatabaseCredential migration,
		String runtimeRole,
		PracticeTenant tenant
	) throws SQLException {
		try (var connection = DriverManager.getConnection(tenantJdbcUrl, migration.username(), migration.password())) {
			connection.setAutoCommit(false);
			try {
				var existing = readTenantIdentity(connection);
				if (existing != null && !existing.practiceId.equals(tenant.practiceId())) {
					throw new TenantProvisioningException(
						"TENANT_DATABASE_IDENTITY_MISMATCH",
						"The tenant database belongs to a different Practice."
					);
				}
				if (existing == null) {
					try (var statement = connection.prepareStatement("""
						INSERT INTO tenant_practice (practice_id, practice_code, display_name, created_at)
						VALUES (?, ?, ?, ?)
						""")) {
						statement.setObject(1, tenant.practiceId());
						statement.setString(2, tenant.code());
						statement.setString(3, tenant.displayName());
						statement.setObject(4, OffsetDateTime.now(clock));
						statement.executeUpdate();
					}
				}
				else if (!existing.code.equals(tenant.code()) || !existing.displayName.equals(tenant.displayName())) {
					throw new TenantProvisioningException(
						"TENANT_DATABASE_IDENTITY_MISMATCH",
						"The tenant database identity does not match the provisioning request."
					);
				}
				try (var statement = connection.prepareStatement("""
					INSERT INTO tenant_administrator_reference (practice_id, user_id, created_at)
					VALUES (?, ?, ?)
					ON CONFLICT (practice_id, user_id) DO NOTHING
					""")) {
					statement.setObject(1, tenant.practiceId());
					statement.setObject(2, tenant.initialAdministratorUserId());
					statement.setObject(3, OffsetDateTime.now(clock));
					statement.executeUpdate();
				}
				execute(connection, "REVOKE INSERT, UPDATE, DELETE ON TABLE tenant_practice FROM "
					+ identifier(runtimeRole));
				execute(connection, "REVOKE INSERT, UPDATE, DELETE ON TABLE tenant_administrator_reference FROM "
					+ identifier(runtimeRole));
				connection.commit();
			}
			catch (RuntimeException | SQLException exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	private void verifyRuntimeRoute(
		String tenantJdbcUrl,
		TenantDatabaseCredential runtime,
		PracticeTenant tenant,
		String databaseName
	) throws SQLException {
		try (var connection = DriverManager.getConnection(tenantJdbcUrl, runtime.username(), runtime.password())) {
			try (var statement = connection.prepareStatement("""
				SELECT current_database(), current_user, practice_id, practice_code, display_name
				FROM tenant_practice
				"""); var resultSet = statement.executeQuery()) {
				if (!resultSet.next()
					|| !databaseName.equals(resultSet.getString(1))
					|| !runtime.username().equals(resultSet.getString(2))
					|| !tenant.practiceId().equals(resultSet.getObject(3, UUID.class))
					|| !tenant.code().equals(resultSet.getString(4))
					|| !tenant.displayName().equals(resultSet.getString(5))
					|| resultSet.next()) {
					throw new TenantProvisioningException(
						"TENANT_DATABASE_VERIFICATION_FAILED",
						"Tenant route verification failed closed."
					);
				}
			}
		}
	}

	private Connection adminConnection() throws SQLException {
		var connection = DriverManager.getConnection(
			properties.adminJdbcUrl(),
			properties.adminUsername(),
			properties.adminPassword()
		);
		connection.setAutoCommit(true);
		return connection;
	}

	private static RoleSecurity roleSecurity(Connection connection, String role) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT rolsuper, rolcreatedb, rolcreaterole, rolbypassrls
			FROM pg_roles
			WHERE rolname = ?
			""")) {
			statement.setString(1, role);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next()
					? new RoleSecurity(
						resultSet.getBoolean(1),
						resultSet.getBoolean(2),
						resultSet.getBoolean(3),
						resultSet.getBoolean(4)
					)
					: null;
			}
		}
	}

	private static String databaseOwner(Connection connection, String database) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT owner.rolname
			FROM pg_database database
			JOIN pg_roles owner ON owner.oid = database.datdba
			WHERE database.datname = ?
			""")) {
			statement.setString(1, database);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getString(1) : null;
			}
		}
	}

	private static boolean hasGrantedRoles(Connection connection, String role) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT EXISTS (
				SELECT 1
				FROM pg_auth_members membership
				JOIN pg_roles member_role ON member_role.oid = membership.member
				WHERE member_role.rolname = ?
			)
			""")) {
			statement.setString(1, role);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getBoolean(1);
			}
		}
	}

	private static TenantIdentity readTenantIdentity(Connection connection) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT practice_id, practice_code, display_name
			FROM tenant_practice
			"""); var resultSet = statement.executeQuery()) {
			if (!resultSet.next()) {
				return null;
			}
			var identity = new TenantIdentity(
				resultSet.getObject(1, UUID.class),
				resultSet.getString(2),
				resultSet.getString(3)
			);
			if (resultSet.next()) {
				throw new TenantProvisioningException(
					"TENANT_DATABASE_IDENTITY_MISMATCH",
					"Tenant database contains more than one Practice identity."
				);
			}
			return identity;
		}
	}

	private static void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static String identifier(String value) {
		assertIdentifier(value);
		return '"' + value + '"';
	}

	private static String literal(String value) {
		if (!value.matches("[A-Za-z0-9_-]{40,80}")) {
			throw new IllegalArgumentException("Generated database password contains unexpected characters.");
		}
		return "'" + value + "'";
	}

	private static void assertIdentifier(String value) {
		if (!SAFE_IDENTIFIER.matcher(value).matches()) {
			throw new IllegalArgumentException("Unsafe server-derived PostgreSQL identifier.");
		}
	}

	private static TenantProvisioningProperties validate(TenantProvisioningProperties properties) {
		if (isBlank(properties.adminJdbcUrl())
			|| isBlank(properties.adminUsername())
			|| properties.adminPassword() == null
			|| isBlank(properties.tenantJdbcUrlTemplate())
			|| !properties.adminJdbcUrl().startsWith("jdbc:postgresql://")
			|| !properties.tenantJdbcUrlTemplate().startsWith("jdbc:postgresql://")
			|| placeholderCount(properties.tenantJdbcUrlTemplate()) != 1
			|| containsEmbeddedCredentials(properties.adminJdbcUrl())
			|| containsEmbeddedCredentials(properties.tenantJdbcUrlTemplate())) {
			throw new IllegalStateException(
				"Enabled tenant provisioning requires admin JDBC credentials and a {database} JDBC URL template."
			);
		}
		return properties;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static int placeholderCount(String value) {
		return value.split(Pattern.quote("{database}"), -1).length - 1;
	}

	private static boolean containsEmbeddedCredentials(String jdbcUrl) {
		var normalized = jdbcUrl.toLowerCase(java.util.Locale.ROOT);
		return normalized.matches(".*[?&](?:user|username|password)=.*");
	}

	private record RoleSecurity(boolean superuser, boolean createDatabase, boolean createRole, boolean bypassRls) {
	}

	private record TenantIdentity(UUID practiceId, String code, String displayName) {
	}
}
