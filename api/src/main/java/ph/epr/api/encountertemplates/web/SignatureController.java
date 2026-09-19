package ph.epr.api.encountertemplates.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.encountertemplates.infrastructure.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/encounters/{encounter}")
class SignatureController {
    private final TenantTransactions tenants;
    private final JdbcEncounterSignatures signatures;
    private final ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts;
    SignatureController(TenantTransactions tenants, JdbcEncounterSignatures signatures,ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts) { this.tenants = tenants; this.signatures = signatures; this.artifacts=artifacts; }
    @PostMapping("/sign-preparations") JdbcEncounterSignatures.Preparation prepare(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID encounter,
        @RequestParam UUID siteId, @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterSignatures.Prepare request) {
        var actor=new AuthenticatedActor(auth.getName());
        var candidate=tenants.execute(actor,practice,siteId,false,c->signatures.captureCandidate(c,patient,encounter,key,request));
        var staged=artifacts.stageDetached(practice,request.preparationId(),candidate.bytes());
        return tenants.execute(actor,practice,siteId,false,c->signatures.prepare(c,patient,encounter,key,request,staged));
    }
    @PostMapping("/signatures") JdbcEncounterSignatures.Signature sign(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID encounter,
        @RequestParam UUID siteId, @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterSignatures.Sign request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> signatures.sign(c, patient, encounter, key, request));
    }
    @GetMapping("/signatures") List<JdbcEncounterSignatures.Signature> list(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID encounter, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> signatures.signatures(c, patient, encounter));
    }
    @GetMapping("/sign-preparations/{preparation}/artifact") ResponseEntity<byte[]> candidate(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID encounter,
        @PathVariable UUID preparation, @RequestParam UUID siteId) {
        return artifact(tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> signatures.candidateBytes(c, patient, encounter, preparation)));
    }
    @GetMapping("/signatures/{signature}/artifact") ResponseEntity<byte[]> signed(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID encounter,
        @PathVariable UUID signature, @RequestParam UUID siteId) {
        return artifact(tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> signatures.signedBytes(c, patient, encounter, signature)));
    }
    @PostMapping("/amendments") JdbcEncounterService.Encounter amend(Authentication auth, @PathVariable UUID practice, @PathVariable UUID patient, @PathVariable UUID encounter,
        @RequestParam UUID siteId, @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcEncounterSignatures.Amendment request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> signatures.amend(c, patient, encounter, key, request));
    }
    private ResponseEntity<byte[]> artifact(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
            .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
