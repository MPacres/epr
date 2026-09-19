package ph.epr.api.carecoordination.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.carecoordination.application.InterventionWorkService;
import ph.epr.api.carecoordination.application.InterventionWorkService.*;
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/intervention-work")
class InterventionWorkController {
 private final TenantTransactions tenants;private final InterventionWorkService work;private final ph.epr.api.identitytenancy.InterventionAuthorization authorization;
 InterventionWorkController(TenantTransactions tenants,InterventionWorkService work,ph.epr.api.identitytenancy.InterventionAuthorization authorization){this.tenants=tenants;this.work=work;this.authorization=authorization;}
 @GetMapping List<InterventionWork> list(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,work::list);}
 @PostMapping("/{kind}/{id}/transfer") InterventionWork transfer(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable String kind,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody TransferWork request){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->work.transfer(c,kind,id,key,request));}
 @PostMapping("/{kind}/{id}/accept") InterventionWork accept(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable String kind,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody AcceptWork request){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->work.accept(c,kind,id,key,request));}
 @GetMapping("/{kind}/owners") List<ph.epr.api.identitytenancy.InterventionAuthorization.OwnerChoice> owners(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable String kind){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->authorization.eligibleOwners(c.actorId(),practice,siteId,kind));}
}
