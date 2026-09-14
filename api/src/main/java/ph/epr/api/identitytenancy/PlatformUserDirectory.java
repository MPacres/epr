package ph.epr.api.identitytenancy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PlatformUserDirectory {

	Optional<PlatformUser> findByUsername(String username);

	PracticeAdministratorSetup preparePracticeAdministrator(
		UUID practiceId,
		String displayName,
		String email,
		UUID requestedByUserId,
		OffsetDateTime now
	);

	TemporaryPasswordIssue issueTemporaryPassword(
		UUID practiceId,
		long expectedVersion,
		UUID idempotencyKey,
		UUID requestedByUserId,
		String reason,
		OffsetDateTime now
	);

	PlatformUser completeRequiredPasswordChange(
		UUID userId,
		String newPassword,
		UUID idempotencyKey,
		OffsetDateTime now
	);
}
