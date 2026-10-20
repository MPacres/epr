package ph.epr.api.clinicalchart.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.clinicalchart.MedicationSafety;
import ph.epr.api.clinicalchart.MedicationSafety.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/medication-safety-reviews")
class MedicationSafetyController {
 private final TenantTransactions tenants;private final MedicationSafety safety;
 MedicationSafetyController(TenantTransactions tenants,MedicationSafety safety){this.tenants=tenants;this.safety=safety;}
 @GetMapping("/{id}") SafetyReview get(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->safety.require(c,patient,id));}
}
