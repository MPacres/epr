package ph.epr.api.encountertemplates.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.encountertemplates.infrastructure.JdbcEncounterService;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/encounters")
class EncounterController {
    private final TenantTransactions tenants;
    private final JdbcEncounterService encounters;
    EncounterController(TenantTransactions tenants, JdbcEncounterService encounters) { this.tenants = tenants; this.encounters = encounters; }
    @GetMapping
    List<JdbcEncounterService.Encounter> list(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.list(c, patient));
    }
    @PostMapping
    JdbcEncounterService.Encounter create(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterService.Create request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.create(c, patient, key, request));
    }
    @PostMapping("/cancel-uncommitted-start")
    JdbcEncounterService.Cancelled cancel(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterService.CancelStart request) {
        var actor = new AuthenticatedActor(auth.getName());
        var result = tenants.execute(actor, practice, siteId, false, c -> {
            var cancelled = encounters.cancelUncommittedStart(c, patient, key, request);
            return new Cancellation(c.actorId(), cancelled);
        });
        encounters.releaseCancelledReservation(result.actor(), practice, result.cancelled());
        return result.cancelled();
    }
    private record Cancellation(UUID actor, JdbcEncounterService.Cancelled cancelled) {}
    @GetMapping("/{id}")
    JdbcEncounterService.Encounter get(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID id, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.get(c, patient, id, true));
    }
    @PutMapping("/{id}/draft")
    JdbcEncounterService.Encounter save(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterService.Save request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.save(c, patient, id, key, request));
    }
    @PostMapping("/{id}/follow-up-intents")
    JdbcEncounterService.FollowUpReceipt followUp(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterService.FollowUp request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.followUp(c, patient, id, key, request));
    }
    @PostMapping("/{id}/care-transitions")
    JdbcEncounterService.Encounter transition(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterService.Transition request) {
        var result = tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.transition(c, patient, id, key, request));
        encounters.releaseFinishedReservation(practice, result);
        return result;
    }
    @PostMapping("/{id}/care-reconciliation")
    JdbcEncounterService.Encounter reconcile(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID id, @RequestParam UUID siteId) {
        var result = tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> encounters.get(c, patient, id, true));
        encounters.releaseFinishedReservation(practice, result);
        return result;
    }
}
