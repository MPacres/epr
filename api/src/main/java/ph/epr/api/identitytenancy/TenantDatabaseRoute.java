package ph.epr.api.identitytenancy;

public record TenantDatabaseRoute(
	String databaseName,
	String jdbcUrl,
	String runtimeSecretReference,
	String migrationSecretReference,
	String schemaVersion
) {
	public TenantDatabaseRoute {
		databaseName = requireNonBlank(databaseName, "Database name");
		jdbcUrl = requireNonBlank(jdbcUrl, "JDBC URL");
		runtimeSecretReference = requireNonBlank(runtimeSecretReference, "Runtime secret reference");
		migrationSecretReference = requireNonBlank(migrationSecretReference, "Migration secret reference");
		schemaVersion = requireNonBlank(schemaVersion, "Schema version");
	}

	private static String requireNonBlank(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " is required for an active tenant route.");
		}
		return value;
	}
}
