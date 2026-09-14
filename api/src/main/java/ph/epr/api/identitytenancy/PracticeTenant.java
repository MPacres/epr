package ph.epr.api.identitytenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PracticeTenant(
	UUID practiceId,
	String code,
	String displayName,
	UUID initialAdministratorUserId,
	PracticeProvisioningStatus status,
	PracticeServiceStatus serviceStatus,
	String databaseName,
	String jdbcUrl,
	String runtimeSecretReference,
	String migrationSecretReference,
	String schemaVersion,
	int provisioningAttempts,
	String failureCode,
	String failureMessage,
	OffsetDateTime suspendedAt,
	String suspensionReason,
	UUID suspendedByUserId,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt,
	long version
) {
	public boolean isRoutable() {
		return status == PracticeProvisioningStatus.ACTIVE
			&& serviceStatus == PracticeServiceStatus.ENABLED
			&& databaseName != null && !databaseName.isBlank()
			&& jdbcUrl != null && !jdbcUrl.isBlank()
			&& runtimeSecretReference != null && !runtimeSecretReference.isBlank();
	}
}
