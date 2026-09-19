package ph.epr.api.encountertemplates.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.encountertemplates.infrastructure.JdbcEncounterService;

@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
class PatientVisitSummaryController {
    private final TenantTransactions tenants;
    private final ClinicalAuthorization authorization;
    private final JdbcEncounterService encounters;
    PatientVisitSummaryController(TenantTransactions tenants,ClinicalAuthorization authorization,JdbcEncounterService encounters) {
        this.tenants=tenants;this.authorization=authorization;this.encounters=encounters;
    }
    @PostMapping("/api/v1/practices/{practice}/patient-directory/visit-summary-refresh")
    Map<String,Boolean> refresh(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId) {
        var actor=new AuthenticatedActor(auth.getName());
        authorization.authorizePatient(actor,practice,siteId,ClinicalAuthorization.PatientOperation.DIRECTORY_READ);
        return tenants.execute(actor,practice,siteId,false,c->{encounters.refreshPatientVisits(c);return Map.of("refreshed",true);});
    }
}
