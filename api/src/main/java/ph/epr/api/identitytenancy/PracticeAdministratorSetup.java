package ph.epr.api.identitytenancy;

import java.util.UUID;

public record PracticeAdministratorSetup(
	UUID userId,
	String displayName,
	String email,
	SetupStatus setupStatus,
	String temporaryPassword
) {
	public enum SetupStatus {
		PASSWORD_NOT_ISSUED,
		TEMPORARY_PASSWORD_ISSUED,
		PASSWORD_SET,
		EXISTING_ACCOUNT
	}

	public PracticeAdministratorSetup withoutTemporaryPassword() {
		return new PracticeAdministratorSetup(userId, displayName, email, setupStatus, null);
	}
}
