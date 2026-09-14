package ph.epr.api.identitytenancy;

import java.util.UUID;

public record PlatformUser(
	UUID id,
	String username,
	String displayName,
	String passwordHash,
	PlatformRole role,
	boolean enabled,
	boolean passwordResetRequired
) {
}
