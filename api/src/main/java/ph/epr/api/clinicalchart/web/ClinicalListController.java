package ph.epr.api.clinicalchart.web;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.clinicalchart.ClinicalLists;
import ph.epr.api.clinicalchart.ClinicalLists.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.SourceKind;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}")
class ClinicalListController {
    private final TenantTransactions tenants;
    private final ClinicalLists lists;
    ClinicalListController(TenantTransactions tenants,ClinicalLists lists){this.tenants=tenants;this.lists=lists;}
    private <T>T run(Authentication authentication,UUID practice,UUID site,Function<TenantTransactions.Context,T> work){
        return tenants.execute(new AuthenticatedActor(authentication.getName()),practice,site,false,work);
    }

    @GetMapping("/clinical-summary") ClinicalSummary summary(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId){return run(auth,practice,siteId,c->lists.summary(c,patient));}
    @GetMapping("/clinical-list-reviews") List<ReviewManifest> reviews(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam(required=false) UUID encounterId){return run(auth,practice,siteId,c->lists.reviews(c,patient,encounterId));}
    @GetMapping("/clinical-record-proposals") List<Proposal> proposals(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId){return run(auth,practice,siteId,c->lists.proposals(c,patient));}
    @PostMapping("/clinical-record-proposals") Proposal propose(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Propose command){return run(auth,practice,siteId,c->lists.propose(c,patient,key,command));}
    @GetMapping("/clinical-terminology") List<Code> terminology(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam SourceKind kind,@RequestParam(defaultValue="") String q){return run(auth,practice,siteId,c->{lists.summary(c,patient);return lists.terminology(c,kind,q);});}

    @GetMapping("/allergies") Page<Allergy> allergies(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam(defaultValue="active") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int pageSize){return run(auth,practice,siteId,c->lists.allergies(c,patient,status,page,pageSize));}
    @PostMapping("/allergies") Allergy createAllergy(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody CreateAllergy command){return run(auth,practice,siteId,c->lists.createAllergy(c,patient,key,command));}
    @GetMapping("/allergies/{id}") Allergy allergy(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId){return run(auth,practice,siteId,c->lists.allergy(c,patient,id));}
    @PostMapping("/allergies/{id}/revisions") Allergy reviseAllergy(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReviseAllergy command){return run(auth,practice,siteId,c->lists.reviseAllergy(c,patient,id,key,command));}
    @PostMapping("/allergies/{id}/transitions") Allergy transitionAllergy(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Transition command){return run(auth,practice,siteId,c->lists.transitionAllergy(c,patient,id,key,command));}
    @PostMapping("/allergy-list-reviews") ReviewManifest reviewAllergies(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReviewCommand command){return run(auth,practice,siteId,c->lists.reviewAllergies(c,patient,key,command));}

    @GetMapping("/medication-use") Page<MedicationUse> medications(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam(defaultValue="active") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int pageSize){return run(auth,practice,siteId,c->lists.medicationUse(c,patient,status,page,pageSize));}
    @PostMapping("/medication-use") MedicationUse createMedication(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody CreateMedicationUse command){return run(auth,practice,siteId,c->lists.createMedicationUse(c,patient,key,command));}
    @GetMapping("/medication-use/{id}") MedicationUse medication(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId){return run(auth,practice,siteId,c->lists.medicationUse(c,patient,id));}
    @PostMapping("/medication-use/{id}/revisions") MedicationUse reviseMedication(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReviseMedicationUse command){return run(auth,practice,siteId,c->lists.reviseMedicationUse(c,patient,id,key,command));}
    @PostMapping("/medication-use/{id}/transitions") MedicationUse transitionMedication(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Transition command){return run(auth,practice,siteId,c->lists.transitionMedicationUse(c,patient,id,key,command));}
    @PostMapping("/medication-list-reviews") ReviewManifest reviewMedications(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReviewCommand command){return run(auth,practice,siteId,c->lists.reviewMedications(c,patient,key,command));}

    @GetMapping("/problems") Page<Problem> problems(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam(defaultValue="active") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int pageSize){return run(auth,practice,siteId,c->lists.problems(c,patient,status,page,pageSize));}
    @PostMapping("/problems") Problem createProblem(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody CreateProblem command){return run(auth,practice,siteId,c->lists.createProblem(c,patient,key,command));}
    @GetMapping("/problems/{id}") Problem problem(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId){return run(auth,practice,siteId,c->lists.problem(c,patient,id));}
    @PostMapping("/problems/{id}/revisions") Problem reviseProblem(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReviseProblem command){return run(auth,practice,siteId,c->lists.reviseProblem(c,patient,id,key,command));}
    @PostMapping("/problems/{id}/transitions") Problem transitionProblem(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Transition command){return run(auth,practice,siteId,c->lists.transitionProblem(c,patient,id,key,command));}
    @PostMapping("/problem-list-reviews") ReviewManifest reviewProblems(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReviewCommand command){return run(auth,practice,siteId,c->lists.reviewProblems(c,patient,key,command));}
}
