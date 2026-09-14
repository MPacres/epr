package ph.epr.api.identitytenancy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PracticeProvisioningRegistry {

	ProvisioningClaim claim(PracticeProvisioningRequest request, OffsetDateTime now);

	PracticeTenant activate(
		UUID practiceId,
		UUID idempotencyKey,
		int expectedAttempt,
		UUID actorUserId,
		TenantDatabaseRoute route,
		OffsetDateTime now
	);

	PracticeTenant quarantine(
		UUID practiceId,
		UUID idempotencyKey,
		int expectedAttempt,
		UUID actorUserId,
		String failureCode,
		String failureMessage,
		OffsetDateTime now
	);

	Optional<PracticeTenant> findByPracticeId(UUID practiceId);

	record ProvisioningClaim(PracticeTenant tenant, boolean provisioningRequired) {
	}
}
