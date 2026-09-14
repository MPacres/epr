package ph.epr.api.identitytenancy;

import java.util.Objects;
import java.util.UUID;

public record PracticeProvisioningRequest(
	UUID practiceId,
	String code,
	String displayName,
	UUID initialAdministratorUserId,
	UUID idempotencyKey,
	UUID requestedByUserId,
	String requestFingerprint
) {
	public PracticeProvisioningRequest {
		Objects.requireNonNull(practiceId, "Practice identifier is required.");
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("Practice code is required.");
		}
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("Practice display name is required.");
		}
		Objects.requireNonNull(initialAdministratorUserId, "Initial administrator is required.");
		Objects.requireNonNull(idempotencyKey, "Idempotency key is required.");
		Objects.requireNonNull(requestedByUserId, "Requesting actor is required.");
		if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Request fingerprint must be a lowercase SHA-256 value.");
		}
	}
}
