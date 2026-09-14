package ph.epr.api.platformoperations.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record TenantCreationRequest(
	UUID practiceId,
	String practiceCode,
	String displayName,
	UUID initialAdministratorUserId,
	UUID idempotencyKey
) {
	public TenantCreationRequest {
		Objects.requireNonNull(practiceId, "Practice identifier is required.");
		practiceCode = TenantIdentifierPolicy.requirePracticeCode(practiceCode);
		displayName = TenantIdentifierPolicy.requireDisplayName(displayName);
		Objects.requireNonNull(initialAdministratorUserId, "Initial administrator is required.");
		Objects.requireNonNull(idempotencyKey, "Idempotency key is required.");
	}

	public String fingerprint() {
		var canonical = practiceId + "\n" + practiceCode + "\n" + displayName + "\n" + initialAdministratorUserId;
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
			);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}
}
