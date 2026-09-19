package ph.epr.api.platformoperations.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
class ClinicalScopesController {
    private final ClinicalAuthorization authorization;
    private final TenantTransactions tenants;
    ClinicalScopesController(ClinicalAuthorization authorization, TenantTransactions tenants) { this.authorization = authorization; this.tenants = tenants; }
    record ClinicalScope(UUID practiceId, UUID siteId, String practiceName, String siteName, String clinicianName, Set<String> patientCapabilities) {}
    @GetMapping("/api/v1/clinical-scopes")
    List<ClinicalScope> scopes(Authentication authentication) {
        var actor = new AuthenticatedActor(authentication.getName());
        return authorization.available(actor).stream().map(scope -> tenants.execute(actor, scope.practiceId(), scope.siteId(), false,
            c -> new ClinicalScope(scope.practiceId(), scope.siteId(), scope.practiceName(),
                c.jdbc().sql("SELECT name FROM practice_site WHERE practice_id = :practice AND site_id = :site")
                    .param("practice", scope.practiceId()).param("site", scope.siteId()).query(String.class).single(), scope.clinicianName(), patientCapabilities(actor, scope)))).toList();
    }
    private Set<String> patientCapabilities(AuthenticatedActor actor, ClinicalAuthorization.ScopeSummary scope) {
        var result=new HashSet<String>();
        for(var operation:ClinicalAuthorization.PatientOperation.values()) {
            try { authorization.authorizePatient(actor,scope.practiceId(),scope.siteId(),operation); result.add(operation.name()); }
            catch(ClinicalAccessException e) { if(e.status()!=403) throw e; }
        }
        return Set.copyOf(result);
    }
}
