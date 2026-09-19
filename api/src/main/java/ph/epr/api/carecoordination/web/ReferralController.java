package ph.epr.api.carecoordination.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.carecoordination.application.ReferralService;
import ph.epr.api.carecoordination.application.ReferralService.*;
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/referrals")
class ReferralController {
 private final TenantTransactions tenants;private final ReferralService service;private final ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts;
 ReferralController(TenantTransactions tenants,ReferralService service,ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts) {this.tenants=tenants;this.service=service;this.artifacts=artifacts;}
 @GetMapping List<ReferralRecord> list(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam(defaultValue="0") int page) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.list(c,patient,page));
 }
 @GetMapping("/{id}") ReferralRecord get(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.get(c,patient,id));
 }
 @GetMapping("/{id}/history") List<ReferralRecord> history(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.history(c,patient,id));
 }
 @PostMapping ReferralRecord create(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralCreate request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.create(c,patient,key,request));
 }
 @PutMapping("/{id}/draft") ReferralRecord update(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralUpdate request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.update(c,patient,id,key,request));
 }
 @PostMapping("/{id}/preparations") ReferralPreparation prepare(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralPrepare request) {
  var actor=new AuthenticatedActor(auth.getName());
  var content=tenants.execute(actor,practice,siteId,false,c->service.candidateContent(c,patient,id,request));
  var staged=artifacts.stageDetached(practice,request.preparationId(),content);
  return tenants.execute(actor,practice,siteId,false,c->service.prepare(c,patient,id,key,request,staged));
 }
 @PostMapping("/{id}/release") ReferralRecord authorize(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralAuthorize request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.authorize(c,patient,id,key,request));
 }
 @PostMapping("/{id}/abandon") ReferralRecord abandon(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralReason request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.abandon(c,patient,id,key,request));
 }
 @PostMapping("/{id}/cancellation") ReferralRecord cancel(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralReason request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.cancel(c,patient,id,key,request));
 }
 @GetMapping("/{id}/artifact") ResponseEntity<byte[]> artifact(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestParam(required=false) UUID preparationId) {
  var bytes=tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.artifact(c,patient,id,preparationId));
  return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'").body(bytes);
 }
  @PostMapping("/{id}/delivery") ReferralRecord delivery(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.delivery(c,patient,id,key,request));
 }
 @PostMapping("/{id}/outcome") ReferralRecord outcome(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.outcome(c,patient,id,key,request));
 }
 @PostMapping("/{id}/review") ReferralRecord review(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.review(c,patient,id,key,request));
 }

 @PostMapping("/{id}/supersession") ReferralRecord supersession(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ReferralCreate request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->{service.get(c,patient,id);return service.create(c,patient,key,new ReferralCreate(request.id(),request.encounterId(),request.occurredAt(),request.data(),id));});
 }
}
