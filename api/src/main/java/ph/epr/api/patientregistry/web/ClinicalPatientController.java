package ph.epr.api.patientregistry.web;

import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.identitytenancy.ClinicalAuthorization.PatientOperation;
import ph.epr.api.patientregistry.*;
import ph.epr.api.patientregistry.PatientRegistrationRecords.*;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}")
class ClinicalPatientController {
    private final TenantTransactions tenants;
    private final ClinicalPatientRegistry patients;
    private final PatientRegistrationRecords registration;
    private final ClinicalAuthorization authorization;
    ClinicalPatientController(TenantTransactions tenants, ClinicalPatientRegistry patients, PatientRegistrationRecords registration, ClinicalAuthorization authorization) {
        this.tenants=tenants; this.patients=patients; this.registration=registration; this.authorization=authorization;
    }
    private <T> T run(Authentication auth, UUID practice, UUID site, PatientOperation operation, Function<TenantTransactions.Context,T> work) {
        var actor = new AuthenticatedActor(auth.getName());
        authorization.authorizePatient(actor, practice, site, operation);
        return tenants.execute(actor, practice, site, false, work);
    }
    @GetMapping("/patients")
    List<ClinicalPatientRegistry.Patient> list(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId) {
        return run(auth,practice,siteId,PatientOperation.DIRECTORY_READ,patients::list);
    }
    @GetMapping("/patient-directory")
    PatientDirectory directory(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId,
        @RequestParam(defaultValue="") String search, @RequestParam(defaultValue="all") String identity,
        @RequestParam(defaultValue="all") String sex, @RequestParam(defaultValue="name") String sort,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int pageSize,
        @RequestParam(defaultValue="false") boolean recent, @RequestParam(defaultValue="all") String visit) {
        return run(auth,practice,siteId,PatientOperation.DIRECTORY_READ,c->registration.directory(c,search,identity,sex,sort,page,pageSize,recent,visit));
    }
    @GetMapping("/patients/{patient}")
    PatientDetail detail(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId) {
        var result = run(auth,practice,siteId,PatientOperation.DETAIL_READ,c->registration.detail(c,patient));
        return capabilities(auth,practice,siteId,result);
    }
    private PatientDetail capabilities(Authentication auth,UUID practice,UUID siteId,PatientDetail result) {
        boolean edit;
        try { authorization.authorizePatient(new AuthenticatedActor(auth.getName()),practice,siteId,PatientOperation.DEMOGRAPHIC_EDIT); edit=true; }
        catch (ClinicalAccessException e) { if(e.status()!=403) throw e; edit=false; }
        return new PatientDetail(result.patient(),result.data(),result.identifiers(),result.originalDisplayName(),edit);
    }
    @PostMapping("/patients")
    PatientDetail create(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key,@RequestBody PatientRegistration request) {
        // This clinician increment continues to a chart; registration alone never grants chart rights.
        authorization.authorizePatient(new AuthenticatedActor(auth.getName()),practice,siteId,PatientOperation.DETAIL_READ);
        return capabilities(auth,practice,siteId,run(auth,practice,siteId,PatientOperation.REGISTER,c->registration.create(c,key,request)));
    }
    @PatchMapping("/patients/{patient}")
    PatientDetail edit(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key,@RequestBody PatientEdit request) {
        authorization.authorizePatient(new AuthenticatedActor(auth.getName()),practice,siteId,PatientOperation.DETAIL_READ);
        return capabilities(auth,practice,siteId,run(auth,practice,siteId,PatientOperation.DEMOGRAPHIC_EDIT,c->registration.edit(c,patient,key,request)));
    }
    @PostMapping("/patients/duplicate-checks")
    DuplicateReview check(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId,@RequestBody DuplicateCheck request) {
        authorization.authorizePatient(new AuthenticatedActor(auth.getName()),practice,siteId,PatientOperation.DIRECTORY_READ);
        return run(auth,practice,siteId,request.excludePatientId()==null?PatientOperation.REGISTER:PatientOperation.DEMOGRAPHIC_EDIT,c->registration.check(c,request));
    }
    @GetMapping("/patients/recent")
    PatientDirectory recent(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId) {
        return run(auth,practice,siteId,PatientOperation.DIRECTORY_READ,c->registration.directory(c,"","all","all","name",0,50,true));
    }
    record ChartOpen(UUID duplicateReviewId) {}
    @PostMapping("/patients/{patient}/chart-opens")
    Map<String,Boolean> open(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key,@RequestBody ChartOpen request) {
        return run(auth,practice,siteId,PatientOperation.DETAIL_READ,c->{registration.open(c,patient,key,request.duplicateReviewId());return Map.of("recorded",true);});
    }
}
