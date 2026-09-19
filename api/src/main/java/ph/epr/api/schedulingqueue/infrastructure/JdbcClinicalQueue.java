package ph.epr.api.schedulingqueue.infrastructure;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterQueueBridge;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
public class JdbcClinicalQueue {
    private final ClinicalPatientRegistry patients;
    private final EncounterQueueBridge encounters;
    private final ClinicalSessionSelection selection;
    private final TenantCommandJournal journal;
    public JdbcClinicalQueue(ClinicalPatientRegistry patients, EncounterQueueBridge encounters, ClinicalSessionSelection selection, TenantCommandJournal journal) {
        this.patients = patients; this.encounters = encounters; this.selection = selection; this.journal = journal;
    }
    public record Session(UUID sessionId, LocalDate sessionDate, String label, String state, long version, boolean selected, long selectionVersion) {}
    public record SessionCreate(UUID sessionId, LocalDate sessionDate, String label) {}
    public record SessionTransition(long expectedVersion, String target, String reason, long expectedSelectionVersion) {}
    public record Entry(UUID queueEntryId, UUID patientId, ClinicalPatientRegistry.Patient patient, String state, UUID encounterId, long version, OffsetDateTime receivedAt) {}
    public record QueueAdd(UUID queueEntryId, UUID patientId, long expectedSessionVersion) {}
    public record QueueTransition(long expectedVersion, String target, String reason) {}
    public record QueueStart(long expectedSessionVersion, long expectedQueueVersion, UUID encounterId, UUID releaseId,
                             UUID episodeId, String careMode, OffsetDateTime clinicalTime) {}
    public List<Session> sessions(Context c) {
        return c.jdbc().sql("SELECT session_id FROM clinical_practice_session WHERE practice_id = :practice AND site_id = :site AND clinician_id = :actor ORDER BY session_date DESC LIMIT 100")
            .param("practice", c.practiceId()).param("site", c.siteId()).param("actor", c.actorId()).query(UUID.class).list().stream().map(id -> session(c, id, false)).toList();
    }
    public Session session(Context c, UUID id, boolean lock) {
        var selected = selection.current(c.actorId());
        return c.jdbc().sql("SELECT * FROM clinical_practice_session WHERE practice_id = :practice AND site_id = :site AND clinician_id = :actor AND session_id = :id" + (lock ? " FOR UPDATE" : ""))
            .param("practice", c.practiceId()).param("site", c.siteId()).param("actor", c.actorId()).param("id", id)
            .query((rs, n) -> new Session(id, rs.getObject("session_date", LocalDate.class), rs.getString("label"), rs.getString("state"), rs.getLong("version"),
                c.practiceId().equals(selected.practiceId()) && id.equals(selected.sessionId()), selected.version()))
            .optional().orElseThrow(() -> new ClinicalAccessException("SESSION_UNAVAILABLE", 404));
    }
    public Session create(Context c, UUID key, SessionCreate request) {
        if (request.sessionId() == null || request.sessionDate() == null || request.label() == null || request.label().isBlank() || request.label().length() > 120)
            throw new ClinicalAccessException("INVALID_SESSION", 422);
        if (c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM clinical_practice_session WHERE practice_id = :practice AND session_id = :id)")
            .param("practice", c.practiceId()).param("id", request.sessionId()).query(Boolean.class).single()) session(c, request.sessionId(), false);
        return journal.execute(c, "create-session", key, request, Session.class, () -> {
            c.jdbc().sql("""
                INSERT INTO clinical_practice_session(practice_id, site_id, session_id, clinician_id, session_date, label, state)
                VALUES (:practice, :site, :id, :actor, :date, :label, 'DRAFT')
                """).param("practice", c.practiceId()).param("site", c.siteId()).param("id", request.sessionId()).param("actor", c.actorId())
                .param("date", request.sessionDate()).param("label", request.label()).update();
            journal.audit(c, request.sessionId(), "SESSION_CREATED", null);
            return session(c, request.sessionId(), false);
        });
    }
    public Session transition(Context c, UUID id, UUID key, SessionTransition request) {
        session(c, id, false); reason(request.reason());
        return journal.execute(c, "session-transition/" + id, key, request, Session.class, () -> {
            var current = session(c, id, true);
            version(current.version(), request.expectedVersion());
            var allowed = Map.of("DRAFT", Set.of("OPEN", "CANCELLED"), "OPEN", Set.of("IN_PROGRESS", "CANCELLED"),
                "IN_PROGRESS", Set.of("IN_PROGRESS", "CLOSING"), "CLOSING", Set.of("CLOSED"));
            if (!allowed.getOrDefault(current.state(), Set.of()).contains(request.target())) throw new ClinicalAccessException("INVALID_SESSION_TRANSITION", 422);
            if (Set.of("CLOSED", "CANCELLED").contains(request.target())) {
                var unresolved = entries(c, id).stream().anyMatch(e -> !terminal(e.state()));
                if (unresolved) throw new ClinicalAccessException("SESSION_HAS_UNRESOLVED_ENTRIES", 409);
            }
            if ("IN_PROGRESS".equals(request.target())) selection.select(c.actorId(), c.practiceId(), id, request.expectedSelectionVersion());
            c.jdbc().sql("UPDATE clinical_practice_session SET state = :state, version = version + 1 WHERE practice_id = :practice AND session_id = :id")
                .param("state", request.target()).param("practice", c.practiceId()).param("id", id).update();
            journal.audit(c, id, "SESSION_" + request.target(), request.reason());
            return session(c, id, false);
        });
    }
    public List<Entry> entries(Context c, UUID session) {
        session(c, session, false);
        return c.jdbc().sql("SELECT queue_entry_id FROM clinical_queue_entry WHERE practice_id = :practice AND session_id = :session ORDER BY received_at")
            .param("practice", c.practiceId()).param("session", session).query(UUID.class).list().stream().map(id -> entry(c, session, id, false)).toList();
    }
    public Entry entry(Context c, UUID session, UUID id, boolean lock) {
        session(c, session, false);
        var value = c.jdbc().sql("SELECT * FROM clinical_queue_entry WHERE practice_id = :practice AND session_id = :session AND queue_entry_id = :id" + (lock ? " FOR UPDATE" : ""))
            .param("practice", c.practiceId()).param("session", session).param("id", id).query((rs, n) -> new Entry(id,
                rs.getObject("patient_id", UUID.class), null, rs.getString("state"), rs.getObject("encounter_id", UUID.class), rs.getLong("version"), rs.getObject("received_at", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new ClinicalAccessException("QUEUE_ENTRY_UNAVAILABLE", 404));
        return new Entry(id, value.patientId(), patients.requirePatient(c, value.patientId()), value.state(), value.encounterId(), value.version(), value.receivedAt());
    }
    public Entry add(Context c, UUID session, UUID key, QueueAdd request) {
        session(c, session, false); patients.requirePatient(c, request.patientId());
        if (request.queueEntryId() == null) throw new ClinicalAccessException("QUEUE_ID_REQUIRED", 422);
        return journal.execute(c, "queue-add/" + session, key, request, Entry.class, () -> {
            var current = session(c, session, true); version(current.version(), request.expectedSessionVersion());
            if (!Set.of("OPEN", "IN_PROGRESS").contains(current.state())) throw new ClinicalAccessException("SESSION_NOT_OPEN", 409);
            c.jdbc().sql("""
                INSERT INTO clinical_queue_entry(practice_id, session_id, queue_entry_id, patient_id, state)
                VALUES (:practice, :session, :id, :patient, 'CHECKED_IN')
                """).param("practice", c.practiceId()).param("session", session).param("id", request.queueEntryId()).param("patient", request.patientId()).update();
            journal.audit(c, request.queueEntryId(), "WALK_IN_CHECKED_IN", null);
            return entry(c, session, request.queueEntryId(), false);
        });
    }
    public Entry transition(Context c, UUID session, UUID id, UUID key, QueueTransition request) {
        entry(c, session, id, false); reason(request.reason());
        return journal.execute(c, "queue-transition/" + id, key, request, Entry.class, () -> {
            session(c, session, true); var current = entry(c, session, id, true); version(current.version(), request.expectedVersion());
            var allowed = Map.of("EXPECTED", Set.of("CHECKED_IN", "CANCELLED", "NO_SHOW"),
                "CHECKED_IN", Set.of("WAITING_FOR_PREPARATION", "READY_FOR_DOCTOR", "CANCELLED", "LEFT_WITHOUT_BEING_SEEN", "DEFERRED"),
                "WAITING_FOR_PREPARATION", Set.of("READY_FOR_DOCTOR", "LEFT_WITHOUT_BEING_SEEN", "DEFERRED"),
                "READY_FOR_DOCTOR", Set.of("CALLED", "LEFT_WITHOUT_BEING_SEEN", "DEFERRED"), "CALLED", Set.of("READY_FOR_DOCTOR", "LEFT_WITHOUT_BEING_SEEN", "DEFERRED"),
                "IN_CONSULTATION", Set.of("COMPLETED"));
            if (!allowed.getOrDefault(current.state(), Set.of()).contains(request.target())) throw new ClinicalAccessException("INVALID_QUEUE_TRANSITION", 422);
            if ("COMPLETED".equals(request.target()) && (current.encounterId() == null || !"FINISHED".equals(encounters.current(c, current.patientId(), current.encounterId()).careState())))
                throw new ClinicalAccessException("CARE_FINISH_REQUIRED", 409);
            c.jdbc().sql("UPDATE clinical_queue_entry SET state = :state, version = version + 1 WHERE practice_id = :practice AND session_id = :session AND queue_entry_id = :id")
                .param("state", request.target()).param("practice", c.practiceId()).param("session", session).param("id", id).update();
            journal.audit(c, id, "QUEUE_" + request.target(), request.reason());
            return entry(c, session, id, false);
        });
    }
    public Entry start(Context c, UUID session, UUID id, UUID key, QueueStart request) {
        entry(c, session, id, false);
        return journal.execute(c, "queue-start/" + id, key, request, Entry.class, () -> {
            var currentSession = session(c, session, true); var entry = entry(c, session, id, true);
            if (entry.encounterId() != null) { encounters.current(c, entry.patientId(), entry.encounterId()); return entry; }
            version(currentSession.version(), request.expectedSessionVersion()); version(entry.version(), request.expectedQueueVersion());
            if (!"IN_PROGRESS".equals(currentSession.state())) throw new ClinicalAccessException("SESSION_ACTIVATION_REQUIRED", 409);
            selection.requireSelected(c.actorId(), c.practiceId(), session);
            if (!Set.of("READY_FOR_DOCTOR", "CALLED").contains(entry.state())) throw new ClinicalAccessException("PATIENT_NOT_READY", 409);
            var created = encounters.start(c, entry.patientId(), key, new EncounterQueueBridge.Start(request.encounterId(), request.releaseId(), request.episodeId(),
                request.careMode(), request.clinicalTime(), session, id, entry.version()));
            c.jdbc().sql("""
                UPDATE clinical_queue_entry SET encounter_id = :encounter, state = 'IN_CONSULTATION', version = version + 1
                WHERE practice_id = :practice AND session_id = :session AND queue_entry_id = :id
                """).param("encounter", created.encounterId()).param("practice", c.practiceId()).param("session", session).param("id", id).update();
            journal.audit(c, id, "QUEUE_CONSULTATION_STARTED", null);
            return entry(c, session, id, false);
        });
    }
    private boolean terminal(String state) { return Set.of("COMPLETED", "CANCELLED", "NO_SHOW", "LEFT_WITHOUT_BEING_SEEN", "TRANSFERRED", "DEFERRED").contains(state); }
    private void version(long actual, long expected) { if (actual != expected) throw new ClinicalAccessException("VERSION_CONFLICT", 409); }
    private void reason(String value) { if (value == null || value.isBlank() || value.length() > 500) throw new ClinicalAccessException("REASON_REQUIRED", 422); }
}
