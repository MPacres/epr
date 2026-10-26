package ph.epr.api.encountertemplates.web;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.clinicalchart.EncounterVitals;
import ph.epr.api.encountertemplates.infrastructure.JdbcEncounterService;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled", havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/encounters/{encounter}/vital-sets")
class EncounterVitalController {
    private final TenantTransactions tenants;
    private final JdbcEncounterService encounters;
    private final EncounterVitals vitals;
    EncounterVitalController(TenantTransactions tenants, JdbcEncounterService encounters, EncounterVitals vitals) {
        this.tenants = tenants; this.encounters = encounters; this.vitals = vitals;
    }
    @GetMapping List<EncounterVitals.VitalSet> list(Authentication auth, @PathVariable UUID practice,
        @PathVariable UUID patient, @PathVariable UUID encounter, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> {
            encounters.get(c, patient, encounter, true);
            return vitals.list(c, patient, encounter);
        });
    }
    @PostMapping EncounterVitals.VitalSet create(Authentication auth, @PathVariable UUID practice,
        @PathVariable UUID patient, @PathVariable UUID encounter, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody EncounterVitals.Create request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> {
            encounters.get(c, patient, encounter, false);
            return vitals.create(c, patient, encounter, key, request);
        });
    }
    @PostMapping("/{id}/corrections") EncounterVitals.VitalSet correct(Authentication auth, @PathVariable UUID practice,
        @PathVariable UUID patient, @PathVariable UUID encounter, @PathVariable UUID id, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody EncounterVitals.Correct request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> {
            encounters.get(c, patient, encounter, false);
            return vitals.correct(c, patient, encounter, id, key, request);
        });
    }
}
