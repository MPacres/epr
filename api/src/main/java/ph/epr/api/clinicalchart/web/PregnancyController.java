package ph.epr.api.clinicalchart.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.clinicalchart.PregnancyRecords;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}")
class PregnancyController {
    private final TenantTransactions tenants;
    private final PregnancyRecords records;
    PregnancyController(TenantTransactions tenants, PregnancyRecords records) { this.tenants = tenants; this.records = records; }
    @GetMapping("/pregnancy-episodes")
    List<PregnancyRecords.Episode> list(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.list(c, patient));
    }
    @PostMapping("/pregnancy-episodes")
    PregnancyRecords.Episode create(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody PregnancyRecords.CreateEpisode request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.create(c, patient, key, request));
    }
    @GetMapping("/pregnancy-episodes/{episode}/dating-assessments")
    List<PregnancyRecords.DatingAssessment> dating(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient,
        @PathVariable UUID episode, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.dating(c, patient, episode));
    }
    @PostMapping("/pregnancy-episodes/{episode}/dating-assessments")
    PregnancyRecords.Episode addDating(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient,
        @PathVariable UUID episode, @RequestParam UUID siteId, @RequestHeader("Idempotency-Key") UUID key, @RequestBody PregnancyRecords.AddDating request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.addDating(c, patient, episode, key, request));
    }
    @PostMapping("/pregnancy-episodes/{episode}/selected-dating-assessment")
    PregnancyRecords.Episode selectDating(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient,
        @PathVariable UUID episode, @RequestParam UUID siteId, @RequestHeader("Idempotency-Key") UUID key, @RequestBody PregnancyRecords.SelectDating request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.selectDating(c, patient, episode, key, request));
    }
    @GetMapping("/obstetric-history")
    List<PregnancyRecords.History> history(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.history(c, patient));
    }
    @PostMapping("/obstetric-history")
    PregnancyRecords.History history(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody PregnancyRecords.SaveHistory request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> records.saveHistory(c, patient, key, request));
    }
}
