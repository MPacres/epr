package ph.epr.api.ordersresults.web;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.ordersresults.application.ServiceRequestService;
import ph.epr.api.ordersresults.application.ServiceRequestService.*;
@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.clinical.enabled",havingValue="true")
@RequestMapping("/api/v1/practices/{practice}/patients/{patient}/service-requests")
class ServiceRequestController {
 private final TenantTransactions tenants;private final ServiceRequestService service;private final ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts;
 ServiceRequestController(TenantTransactions tenants,ServiceRequestService service,ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts) {this.tenants=tenants;this.service=service;this.artifacts=artifacts;}
 @GetMapping List<ServiceRequestRecord> list(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestParam(defaultValue="0") int page) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.list(c,patient,page));
 }
 @GetMapping("/{id}") ServiceRequestRecord get(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.get(c,patient,id));
 }
 @GetMapping("/{id}/history") List<ServiceRequestRecord> history(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.history(c,patient,id));
 }
 @PostMapping ServiceRequestRecord create(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestCreate request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.create(c,patient,key,request));
 }
 @PutMapping("/{id}/draft") ServiceRequestRecord update(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestUpdate request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.update(c,patient,id,key,request));
 }
 @PostMapping("/{id}/preparations") ServiceRequestPreparation prepare(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestPrepare request) {
  var actor=new AuthenticatedActor(auth.getName());
  var content=tenants.execute(actor,practice,siteId,false,c->service.candidateContent(c,patient,id,request));
  var staged=artifacts.stageDetached(practice,request.preparationId(),content);
  return tenants.execute(actor,practice,siteId,false,c->service.prepare(c,patient,id,key,request,staged));
 }
 @PostMapping("/{id}/authorization") ServiceRequestRecord authorize(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestAuthorize request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.authorize(c,patient,id,key,request));
 }
 @PostMapping("/{id}/abandon") ServiceRequestRecord abandon(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestReason request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.abandon(c,patient,id,key,request));
 }
 @PostMapping("/{id}/cancellation") ServiceRequestRecord cancel(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestReason request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.cancel(c,patient,id,key,request));
 }
 @GetMapping("/{id}/artifact") ResponseEntity<byte[]> artifact(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestParam(required=false) UUID preparationId) {
  var bytes=tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.artifact(c,patient,id,preparationId));
  return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'").body(bytes);
 }
  @PostMapping("/{id}/delivery") ServiceRequestRecord delivery(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.delivery(c,patient,id,key,request));
 }
 @PostMapping("/{id}/fulfillment") ServiceRequestRecord fulfillment(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.fulfillment(c,patient,id,key,request));
 }
 @PostMapping("/{id}/result") ServiceRequestRecord result(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.result(c,patient,id,key,request));
 }
 @PostMapping("/{id}/review") ServiceRequestRecord review(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestEvidence request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.review(c,patient,id,key,request));
 }
 @GetMapping("/catalog") List<ServiceCatalogEntry> catalog(Authentication auth,@PathVariable UUID practice,@RequestParam UUID siteId) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,service::catalog);
 }
 @PostMapping("/groups") ServiceRequestBatchResult batch(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestBatch request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.createBatch(c,patient,key,request));
 }
 @GetMapping("/groups/{group}") List<ServiceRequestRecord> group(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID group,@RequestParam UUID siteId) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.group(c,patient,group));
 }

 @PostMapping("/{id}/supersession") ServiceRequestRecord supersession(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID id,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ServiceRequestCreate request) {
  return tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->{service.get(c,patient,id);return service.create(c,patient,key,new ServiceRequestCreate(request.id(),request.encounterId(),request.occurredAt(),request.data(),id));});
 }
 @PostMapping("/groups/{group}/documents") GroupDocument groupDocument(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID group,@RequestParam UUID siteId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody GroupPrint request){
  var actor=new AuthenticatedActor(auth.getName());var capture=tenants.execute(actor,practice,siteId,false,c->service.captureGroup(c,patient,group,key,request));
  var staged=artifacts.stageDetached(practice,request.documentId(),capture.bytes());return tenants.execute(actor,practice,siteId,false,c->service.finalizeGroup(c,patient,key,capture,staged));
 }
 @GetMapping("/groups/{group}/documents/{id}") ResponseEntity<byte[]> groupArtifact(Authentication auth,@PathVariable UUID practice,@PathVariable UUID patient,@PathVariable UUID group,@PathVariable UUID id,@RequestParam UUID siteId){
  var bytes=tenants.execute(new AuthenticatedActor(auth.getName()),practice,siteId,false,c->service.groupArtifact(c,patient,group,id));return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'").body(bytes);
 }
}
