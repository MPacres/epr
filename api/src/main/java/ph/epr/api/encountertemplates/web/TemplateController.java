package ph.epr.api.encountertemplates.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.encountertemplates.infrastructure.JdbcTemplateRegistry;
import ph.epr.api.encountertemplates.domain.TemplateCompiler;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}")
class TemplateController {
    private final TenantTransactions tenants;
    private final JdbcTemplateRegistry registry;
    TemplateController(TenantTransactions tenants, JdbcTemplateRegistry registry) { this.tenants = tenants; this.registry = registry; }
    @GetMapping("/encounter-templates")
    List<JdbcTemplateRegistry.Release> catalog(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId,
        @RequestParam(required = false) String specialty, @RequestParam(required = false) String encounterType) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> registry.catalog(c, specialty, encounterType));
    }
    @GetMapping("/encounter-template-releases/{id}")
    JdbcTemplateRegistry.Release release(Authentication auth, @PathVariable UUID practice, @PathVariable UUID id, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> registry.release(c, id, false));
    }
    @PostMapping("/encounter-template-drafts")
    JdbcTemplateRegistry.Draft create(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcTemplateRegistry.DraftCommand request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, true, c -> registry.saveDraft(c, key, request, true));
    }
    @PutMapping("/encounter-template-drafts/{id}")
    JdbcTemplateRegistry.Draft update(Authentication auth, @PathVariable UUID practice, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcTemplateRegistry.DraftCommand request) {
        if (!id.equals(request.draftId())) throw new ClinicalAccessException("DRAFT_ID_MISMATCH", 422);
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, true, c -> registry.saveDraft(c, key, request, false));
    }
    @PostMapping("/encounter-template-drafts/{id}/validation")
    Map<String, Object> validate(Authentication auth, @PathVariable UUID practice, @PathVariable UUID id, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, true, c -> {
            var draft = registry.draft(c, id);
            new TemplateCompiler().validate(draft.definition());
            return Map.of("valid", true, "checksum", draft.checksum(), "syntheticOnly", true);
        });
    }
    @PostMapping("/encounter-template-drafts/{id}/approval")
    JdbcTemplateRegistry.Draft approve(Authentication auth, @PathVariable UUID practice, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcTemplateRegistry.Approval request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, true, c -> registry.approve(c, id, key, request));
    }
    @PostMapping("/encounter-template-drafts/{id}/publication")
    JdbcTemplateRegistry.Release publish(Authentication auth, @PathVariable UUID practice, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcTemplateRegistry.Publication request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, true, c -> registry.publish(c, id, key, request));
    }
    @PostMapping("/encounter-template-releases/{id}/retirement")
    JdbcTemplateRegistry.Release retire(Authentication auth, @PathVariable UUID practice, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcTemplateRegistry.Availability request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, true, c -> registry.availability(c, id, key, request));
    }
}
