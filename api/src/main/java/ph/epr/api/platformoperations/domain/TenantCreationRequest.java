package ph.epr.api.platformoperations.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record TenantCreationRequest(
	UUID practiceId,
	String practiceCode,
	String displayName,
	String administratorName,
	String administratorEmail,
	InitialTenantSite initialSite,
	UUID idempotencyKey
) {
	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	public TenantCreationRequest {
		Objects.requireNonNull(practiceId, "Practice identifier is required.");
		practiceCode = TenantIdentifierPolicy.requirePracticeCode(practiceCode);
		displayName = TenantIdentifierPolicy.requireDisplayName(displayName);
		administratorName = TenantIdentifierPolicy.requireDisplayName(administratorName);
		administratorEmail = Objects.requireNonNull(administratorEmail, "Administrator email is required.")
			.strip()
			.toLowerCase(Locale.ROOT);
		if (administratorEmail.length() > 160 || !EMAIL.matcher(administratorEmail).matches()) {
			throw new IllegalArgumentException("Administrator email must be a valid address under 160 characters.");
		}
		Objects.requireNonNull(initialSite, "Initial site is required.");
		Objects.requireNonNull(idempotencyKey, "Idempotency key is required.");
	}

	public String fingerprint() {
		var canonical = String.join("\n",
			practiceId.toString(),
			practiceCode,
			displayName,
			administratorName,
			administratorEmail,
			initialSite.siteId().toString(),
			initialSite.name(),
			initialSite.facilityName(),
			initialSite.contactNumber(),
			initialSite.countryCode(),
			initialSite.regionCode(),
			initialSite.provinceAreaCode(),
			initialSite.localityCode()
		);
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
