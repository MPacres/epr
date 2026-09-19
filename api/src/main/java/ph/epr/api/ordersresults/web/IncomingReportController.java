package ph.epr.api.ordersresults.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.ordersresults.application.IncomingReportService;
import ph.epr.api.ordersresults.application.IncomingReportService.*;
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/incoming-service-reports")
class IncomingReportController {
 private final TenantTransactions tenants;private final IncomingReportService reports;
 IncomingReportController(TenantTransactions tenants,IncomingReportService reports){this.tenants=tenants;this.reports=reports;}
 @GetMapping List<IncomingReport> unmatched(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,reports::unmatched);}
 @PostMapping IncomingReport receive(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReceiveReport request){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->reports.receive(c,key,request));}
 @GetMapping("/{id}") IncomingReport get(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable UUID id){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->reports.get(c,id));}
 @PostMapping("/{id}/match") IncomingReport match(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody MatchReport request){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->reports.match(c,id,key,request));}
}
