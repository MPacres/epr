package ph.epr.api.schedulingqueue.web;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue;

@RestController
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.enabled", havingValue = "true")
@RequestMapping("/api/v1/practices/{practice}/sessions")
class ClinicalQueueController {
    private final TenantTransactions tenants;
    private final JdbcClinicalQueue queue;
    ClinicalQueueController(TenantTransactions tenants, JdbcClinicalQueue queue) { this.tenants = tenants; this.queue = queue; }
    @GetMapping List<JdbcClinicalQueue.Session> sessions(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, queue::sessions);
    }
    @PostMapping JdbcClinicalQueue.Session create(Authentication auth, @PathVariable UUID practice, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcClinicalQueue.SessionCreate request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> queue.create(c, key, request));
    }
    @PostMapping("/{session}/transitions") JdbcClinicalQueue.Session transition(Authentication auth, @PathVariable UUID practice, @PathVariable UUID session, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcClinicalQueue.SessionTransition request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> queue.transition(c, session, key, request));
    }
    @GetMapping("/{session}/queue-entries") List<JdbcClinicalQueue.Entry> entries(Authentication auth, @PathVariable UUID practice, @PathVariable UUID session, @RequestParam UUID siteId) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> queue.entries(c, session));
    }
    @PostMapping("/{session}/queue-entries") JdbcClinicalQueue.Entry add(Authentication auth, @PathVariable UUID practice, @PathVariable UUID session, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcClinicalQueue.QueueAdd request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> queue.add(c, session, key, request));
    }
    @PostMapping("/{session}/queue-entries/{entry}/transitions") JdbcClinicalQueue.Entry entryTransition(Authentication auth, @PathVariable UUID practice, @PathVariable UUID session, @PathVariable UUID entry, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcClinicalQueue.QueueTransition request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> queue.transition(c, session, entry, key, request));
    }
    @PostMapping("/{session}/queue-entries/{entry}/start-consultation") JdbcClinicalQueue.Entry start(Authentication auth, @PathVariable UUID practice, @PathVariable UUID session, @PathVariable UUID entry, @RequestParam UUID siteId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestBody JdbcClinicalQueue.QueueStart request) {
        return tenants.execute(new AuthenticatedActor(auth.getName()), practice, siteId, false, c -> queue.start(c, session, entry, key, request));
    }
}
