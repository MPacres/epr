package ph.epr.api.platformoperations.web;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.platformoperations.application.TenantSchemaUpgradeService;

@RestController
@Profile("local")
class TenantSchemaUpgradeController {
    private final PlatformUserDirectory users;
    private final TenantSchemaUpgradeService service;
    TenantSchemaUpgradeController(PlatformUserDirectory users, TenantSchemaUpgradeService service) { this.users = users; this.service = service; }
    record Upgrade(long expectedVersion, String reason) {}
    @PostMapping("/api/v1/admin/practices/{practice}/schema-upgrade")
    @PreAuthorize("hasRole('SUPERADMIN')")
    TenantSchemaUpgradeService.Result upgrade(Authentication auth, @PathVariable UUID practice, @RequestHeader("Idempotency-Key") UUID key, @RequestBody Upgrade request) {
        return service.upgrade(users.findByUsername(auth.getName()).orElseThrow(), practice, key, request.expectedVersion(), request.reason());
    }
}
