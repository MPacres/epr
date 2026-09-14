package ph.epr.api.identitytenancy;

import java.util.Optional;

public interface PlatformUserDirectory {

	Optional<PlatformUser> findByUsername(String username);
}

