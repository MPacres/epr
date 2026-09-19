package ph.epr.api.platformoperations.infrastructure;

import java.util.UUID;

interface TenantCredentialStore {

	TenantDatabaseCredential getOrCreate(UUID practiceId, CredentialPurpose purpose, String username);

	default TenantDatabaseCredential resolveRuntime(UUID practiceId, String reference) {
		throw new IllegalStateException("Runtime credential resolution is unavailable.");
	}

	default TenantDatabaseCredential resolveMigration(UUID practiceId, String reference) {
        throw new IllegalStateException("Migration credential resolution is unavailable.");
    }

	enum CredentialPurpose {
		RUNTIME,
		MIGRATION
	}
}
