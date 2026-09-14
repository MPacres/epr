package ph.epr.api.identitytenancy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PracticeAdministrationRegistry {

	PracticeAdministrationPage list(
		String search,
		PracticeProvisioningStatus provisioningStatus,
		PracticeServiceStatus serviceStatus,
		int page,
		int size
	);

	Optional<PracticeAdministrationView> findAdministrationView(UUID practiceId);

	PracticeAdministrationView updateDisplayName(
		UUID practiceId,
		String displayName,
		long expectedVersion,
		UUID idempotencyKey,
		UUID actorUserId,
		OffsetDateTime now
	);

	PracticeAdministrationView changeServiceStatus(
		UUID practiceId,
		PracticeServiceStatus targetStatus,
		String reason,
		long expectedVersion,
		UUID idempotencyKey,
		UUID actorUserId,
		OffsetDateTime now
	);
}
