package ph.epr.api.carecoordination.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist;
@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}/clinical-worklist")
class ClinicalWorklistController {
    private final TenantTransactions tenants;
    private final JdbcClinicalWorklist worklist;
    ClinicalWorklistController(TenantTransactions tenants, JdbcClinicalWorklist worklist) { this.tenants = tenants; this.worklist = worklist; }
    @GetMapping List<JdbcClinicalWorklist.Task> list(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, worklist::list);
    }
    @PostMapping("/reconcile") List<JdbcClinicalWorklist.Task> reconcile(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, worklist::reconcile);
    }
    @PostMapping("/{id}/resolve") JdbcClinicalWorklist.Task resolve(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId, @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcClinicalWorklist.Resolve request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> worklist.resolve(c, id, key, request));
    }
    @PostMapping("/{id}/transfer") JdbcClinicalWorklist.Task transfer(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JdbcClinicalWorklist.TransferFollowUp request){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->worklist.transfer(c,id,key,request));}
    @PostMapping("/{id}/accept") JdbcClinicalWorklist.Task accept(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JdbcClinicalWorklist.AcceptFollowUp request){return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->worklist.accept(c,id,key,request));}
}
