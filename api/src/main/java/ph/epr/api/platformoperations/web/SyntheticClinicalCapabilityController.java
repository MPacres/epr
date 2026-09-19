package ph.epr.api.platformoperations.web;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.SyntheticClinicalCapability;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
class SyntheticClinicalCapabilityController {
    private final PlatformUserDirectory users;
    private final SyntheticClinicalCapability capability;

    SyntheticClinicalCapabilityController(PlatformUserDirectory users, SyntheticClinicalCapability capability) {
        this.users = users;
        this.capability = capability;
    }

    record Enable(long expectedVersion, String reason) {}

    @PostMapping("/api/v1/admin/practices/{practice}/synthetic-clinical-capability")
    @PreAuthorize("hasRole('SUPERADMIN')")
    SyntheticClinicalCapability.Result enable(Authentication authentication, @PathVariable UUID practice,
        @RequestHeader("Idempotency-Key") UUID operation, @RequestBody Enable request) {
        return capability.enable(users.findByUsername(authentication.getName()).orElseThrow(), practice,
            operation, request.expectedVersion(), request.reason());
    }
}
