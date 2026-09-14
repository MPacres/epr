package ph.epr.api.platformoperations.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TenantIdentifierPolicy {

	private static final Pattern PRACTICE_CODE = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");
	private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");

	private TenantIdentifierPolicy() {
	}

	public static String requirePracticeCode(String value) {
		var code = Objects.requireNonNull(value, "Practice code is required.").strip();
		if (!PRACTICE_CODE.matcher(code).matches()) {
			throw new IllegalArgumentException(
				"Practice code must be 1-32 lowercase letters, numbers, or internal hyphens."
			);
		}
		return code;
	}

	public static String requireDisplayName(String value) {
		var displayName = Objects.requireNonNull(value, "Display name is required.").strip();
		if (displayName.length() < 2 || displayName.length() > 160 || CONTROL_CHARACTERS.matcher(displayName).find()) {
			throw new IllegalArgumentException("Display name must be 2-160 printable characters.");
		}
		return displayName;
	}

	public static String databaseName(UUID practiceId) {
		return "epr_t_" + compact(practiceId);
	}

	public static String runtimeRole(UUID practiceId) {
		return "epr_r_" + compact(practiceId);
	}

	public static String migrationRole(UUID practiceId) {
		return "epr_m_" + compact(practiceId);
	}

	private static String compact(UUID practiceId) {
		return Objects.requireNonNull(practiceId, "Practice identifier is required.")
			.toString()
			.replace("-", "")
			.toLowerCase(Locale.ROOT);
	}
}
