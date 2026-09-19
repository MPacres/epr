package ph.epr.api.ordersresults.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.ordersresults.application.IncomingReportService;
@RestController @Profile("local") @ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
class MatchedReportController {
 private final TenantTransactions tenants;private final IncomingReportService reports;
 MatchedReportController(TenantTransactions tenants,IncomingReportService reports){this.tenants=tenants;this.reports=reports;}
 @GetMapping("/api/v1/practices/{practice}/patients/{patient}/service-requests/{id}/reports") List<IncomingReportService.IncomingReport> list(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->reports.forRequest(c,patient,id));}
}
