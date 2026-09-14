package ph.epr.api.platformoperations.infrastructure;

import java.util.UUID;

interface TenantCredentialStore {

	TenantDatabaseCredential getOrCreate(UUID practiceId, CredentialPurpose purpose, String username);

	enum CredentialPurpose {
		RUNTIME,
		MIGRATION
	}
}
