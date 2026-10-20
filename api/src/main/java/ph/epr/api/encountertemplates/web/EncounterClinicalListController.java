package ph.epr.api.encountertemplates.web;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.clinicalchart.ClinicalLists;
import ph.epr.api.clinicalchart.ClinicalLists.*;
import ph.epr.api.encountertemplates.infrastructure.JdbcEncounterService;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;

/** Encounter-owned validation before clinicalchart applies encounter-scoped commands. */
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}")
class EncounterClinicalListController {
    private final TenantTransactions tenants;private final ClinicalLists lists;private final JdbcEncounterService encounters;
    EncounterClinicalListController(TenantTransactions tenants,ClinicalLists lists,JdbcEncounterService encounters){this.tenants=tenants;this.lists=lists;this.encounters=encounters;}
    @PostMapping("/clinical-list-reconciliations") ReviewManifest reconcile(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Reconcile command){
        return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->{encounters.get(c,patient,command.encounterId(),false);return lists.reconcile(c,patient,key,command);});
    }
    @PostMapping("/problems/{problem}/encounter-links") Problem link(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID problem,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody EncounterLink command){
        return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->{encounters.get(c,patient,command.encounterId(),false);return lists.linkProblem(c,patient,problem,key,command);});
    }
}
