package ph.epr.api.encountertemplates.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.encountertemplates.EncounterInterventionLinks;
import ph.epr.api.encountertemplates.EncounterInterventionLinks.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.TenantTransactions;
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/encounters/{encounter}")
class InterventionController {
 private final TenantTransactions tenants;private final EncounterInterventionLinks links;
 InterventionController(TenantTransactions tenants,EncounterInterventionLinks links){this.tenants=tenants;this.links=links;}
 @GetMapping("/intervention-capabilities") List<InterventionAuthorization.Capability> capabilities(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID encounter,@RequestParam UUID siteId){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->links.capabilities(c,patient,encounter));}
 @GetMapping("/interventions") Page list(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID encounter,@RequestParam UUID siteId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->links.list(c,patient,encounter,page,size));}
 @GetMapping("/intervention-manifests/{id}") Manifest manifest(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID encounter,@PathVariable UUID id,@RequestParam UUID siteId){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->links.manifest(c,patient,encounter,id));}
}
