package ph.epr.api.platformoperations.application;

import java.util.UUID;

public class TenantNotFoundException extends RuntimeException {

	public TenantNotFoundException(UUID practiceId) {
		super("Practice tenant was not found: " + practiceId);
	}
}
