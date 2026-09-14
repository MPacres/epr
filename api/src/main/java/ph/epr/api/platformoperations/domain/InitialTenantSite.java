package ph.epr.api.platformoperations.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record InitialTenantSite(
	UUID siteId,
	String name,
	String facilityName,
	String contactNumber,
	String countryCode,
	String regionCode,
	String provinceAreaCode,
	String localityCode
) {
	private static final Pattern PSGC_CODE = Pattern.compile("[0-9]{10}");
	private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");

	public InitialTenantSite {
		Objects.requireNonNull(siteId, "Initial site identifier is required.");
		name = requirePrintable(name, "Initial site name", 2, 120);
		facilityName = optionalPrintable(facilityName, "Facility name", 160);
		contactNumber = optionalPrintable(contactNumber, "Contact number", 30);
		if (!"PH".equals(countryCode)) {
			throw new IllegalArgumentException("The initial site country must be PH.");
		}
		regionCode = requirePsgcCode(regionCode, "Region");
		provinceAreaCode = requirePsgcCode(provinceAreaCode, "Province or independent area");
		localityCode = requirePsgcCode(localityCode, "City or municipality");
	}

	private static String requirePrintable(String value, String label, int minimum, int maximum) {
		var normalized = Objects.requireNonNull(value, label + " is required.").strip();
		if (normalized.length() < minimum || normalized.length() > maximum || CONTROL_CHARACTERS.matcher(normalized).find()) {
			throw new IllegalArgumentException(label + " must be " + minimum + "-" + maximum + " printable characters.");
		}
		return normalized;
	}

	private static String optionalPrintable(String value, String label, int maximum) {
		var normalized = Objects.requireNonNullElse(value, "").strip();
		if (normalized.length() > maximum || CONTROL_CHARACTERS.matcher(normalized).find()) {
			throw new IllegalArgumentException(label + " must be at most " + maximum + " printable characters.");
		}
		return normalized;
	}

	private static String requirePsgcCode(String value, String label) {
		var normalized = Objects.requireNonNull(value, label + " PSGC code is required.").strip();
		if (!PSGC_CODE.matcher(normalized).matches()) {
			throw new IllegalArgumentException(label + " PSGC code must contain exactly 10 digits.");
		}
		return normalized;
	}
}
