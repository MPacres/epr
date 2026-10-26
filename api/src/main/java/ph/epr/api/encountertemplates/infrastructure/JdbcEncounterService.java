package ph.epr.api.encountertemplates.infrastructure;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.clinicalchart.PregnancyRecords;
import ph.epr.api.encountertemplates.domain.*;
import ph.epr.api.encountertemplates.EncounterQueueBridge;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
public class JdbcEncounterService {
    private final ClinicalPatientRegistry patients;
    private final PregnancyRecords pregnancies;
    private final JdbcTemplateRegistry templates;
    private final TenantCommandJournal journal;
    private final ActiveCareReservations reservations;
    private final ph.epr.api.encountertemplates.EncounterInterventionLinks interventions;
    public JdbcEncounterService(ClinicalPatientRegistry patients, PregnancyRecords pregnancies, JdbcTemplateRegistry templates,
                                TenantCommandJournal journal, ActiveCareReservations reservations, ph.epr.api.encountertemplates.EncounterInterventionLinks interventions) {
        this.patients = patients; this.pregnancies = pregnancies; this.templates = templates; this.journal = journal; this.reservations = reservations; this.interventions=interventions;
    }
    public record Create(UUID encounterId, UUID releaseId, UUID episodeId, String careMode, OffsetDateTime clinicalTime) {}
    public record Change(UUID fieldId, UUID groupItemId, Answer answer) {}
    public record Save(long expectedVersion, OffsetDateTime clientTime, List<Change> changes) {
        public Save { changes = List.copyOf(changes); }
    }
    public record FollowUp(UUID intentId, long expectedVersion, java.time.LocalDate dueDate, String instruction) {}
    public record FollowUpReceipt(UUID intentId, UUID ownerId, String deliveryState) {}
    public record Transition(long expectedVersion, String target, String reason) {}
    public record CancelStart(UUID encounterId, String reason) {}
    public record Cancelled(UUID encounterId, long fence) {}
    public record Encounter(UUID encounterId, UUID patientId, UUID siteId, UUID responsibleClinicianId,
                            ClinicalPatientRegistry.Patient patient, JdbcTemplateRegistry.Release release,
                            PregnancyRecords.Snapshot episodeSnapshot, String careMode, String careState, String noteState,
                            OffsetDateTime clinicalTime, OffsetDateTime receivedAt, OffsetDateTime endedAt,
                            long version, long responseRevision, UUID unsignedNoteOwner, long reservationFence,
                            List<Answer> answers, OffsetDateTime savedAt, UUID sessionId, UUID queueEntryId) {}

    public Encounter create(Context c, UUID patient, UUID key, Create request) {
        return createInternal(c, patient, key, request, null);
    }
    public Encounter createQueued(Context c, UUID patient, UUID key, EncounterQueueBridge.Start request) {
        return createInternal(c, patient, key, new Create(request.encounterId(), request.releaseId(), request.episodeId(), request.careMode(), request.clinicalTime()), request);
    }
    private Encounter createInternal(Context c, UUID patient, UUID key, Create request, EncounterQueueBridge.Start handoff) {
        patients.requirePatient(c, patient);
        if (request.encounterId() == null || request.releaseId() == null || request.clinicalTime() == null
            || request.careMode() == null || !Set.of("IN_PERSON", "TELEPHONE", "TELECONSULTATION").contains(request.careMode()))
            throw new ClinicalAccessException("INVALID_ENCOUNTER", 422);
        if (c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM clinical_encounter WHERE practice_id = :practice AND encounter_id = :id)")
            .param("practice", c.practiceId()).param("id", request.encounterId()).query(Boolean.class).single()) get(c, patient, request.encounterId(), false);
        templates.release(c, request.releaseId(), false); // Reauthorize restricted content even for a receipt replay.
        if (request.episodeId() != null && !c.reproductiveAccess()) throw new ClinicalAccessException("REPRODUCTIVE_ACCESS_DENIED", 403);
        lockClinician(c);
        return journal.execute(c, "create-encounter/" + patient, key, handoff == null ? request : handoff, Encounter.class, () -> {
            if (c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM cancelled_encounter_start WHERE practice_id = :practice AND encounter_id = :id)")
                .param("practice", c.practiceId()).param("id", request.encounterId()).query(Boolean.class).single())
                throw new ClinicalAccessException("ENCOUNTER_START_CANCELLED", 409);
            var release = templates.release(c, request.releaseId(), true);
            if (release.definition().requiresPregnancyEpisode() && request.episodeId() == null)
                throw new ClinicalAccessException("PREGNANCY_EPISODE_REQUIRED", 422);
            var snapshot = request.episodeId() == null ? null : pregnancies.snapshot(c, patient, request.episodeId());
            // Reserve only after tenant validation. A crash/rollback after this point retains the reservation.
            var fence = handoff == null ? reservations.reserve(c.actorId(), c.practiceId(), request.encounterId(), creationHash(c, patient, key, request))
                : reservations.reserveQueued(c.actorId(), c.practiceId(), request.encounterId(), ClinicalJson.hash(Map.of("patient", patient, "site", c.siteId(), "key", key, "request", handoff)), handoff.sessionId());
            c.jdbc().sql("""
                INSERT INTO clinical_encounter(practice_id, patient_id, encounter_id, site_id, responsible_clinician_id,
                    release_id, release_checksum, episode_id, episode_snapshot, care_mode, clinical_time, unsigned_note_owner, reservation_fence)
                VALUES (:practice, :patient, :id, :site, :actor, :release, :checksum, :episode, CAST(:snapshot AS jsonb), :mode, :time, :actor, :fence)
                """).param("practice", c.practiceId()).param("patient", patient).param("id", request.encounterId()).param("site", c.siteId())
                .param("actor", c.actorId()).param("release", request.releaseId()).param("checksum", release.checksum())
                .param("episode", request.episodeId(), Types.OTHER).param("snapshot", snapshot == null ? null : ClinicalJson.write(snapshot), Types.VARCHAR)
                .param("mode", request.careMode()).param("time", request.clinicalTime()).param("fence", fence).update();
            if (handoff != null) c.jdbc().sql("""
                UPDATE clinical_encounter SET session_id = :session, queue_entry_id = :queue, handoff_snapshot = CAST(:snapshot AS jsonb)
                WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id
                """).param("session", handoff.sessionId()).param("queue", handoff.queueEntryId()).param("snapshot", ClinicalJson.write(handoff))
                .param("practice", c.practiceId()).param("patient", patient).param("id", request.encounterId()).update();
            journal.audit(c, request.encounterId(), handoff == null ? "NON_QUEUED_ENCOUNTER_CREATED" : "QUEUED_ENCOUNTER_CREATED", null);
            journal.event(c, request.encounterId(), "UNSIGNED_NOTE_CREATED", c.actorId());
            patients.visitsChanged(c);
            return get(c, patient, request.encounterId(), false);
        });
    }
    /** Serialize against delayed starts in this tenant. Commit the tombstone before releasing the control-plane fence. */
    public Cancelled cancelUncommittedStart(Context c, UUID patient, UUID key, CancelStart request) {
        patients.requirePatient(c, patient);
        if (request.encounterId() == null || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500)
            throw new ClinicalAccessException("CANCELLATION_REASON_REQUIRED", 422);
        lockClinician(c);
        if (c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM clinical_encounter WHERE practice_id = :practice AND encounter_id = :id)")
            .param("practice", c.practiceId()).param("id", request.encounterId()).query(Boolean.class).single())
            throw new ClinicalAccessException("ENCOUNTER_COMMITTED_RESUME_REQUIRED", 409);
        return journal.execute(c, "cancel-start/" + patient, key, request, Cancelled.class, () -> {
            var fence = reservations.fence(c.actorId(), c.practiceId(), request.encounterId()).orElse(0);
            c.jdbc().sql("INSERT INTO cancelled_encounter_start(practice_id, encounter_id, clinician_id, reason) VALUES (:practice, :id, :actor, :reason) ON CONFLICT DO NOTHING")
                .param("practice", c.practiceId()).param("id", request.encounterId()).param("actor", c.actorId()).param("reason", request.reason()).update();
            journal.audit(c, request.encounterId(), "UNCOMMITTED_START_CANCELLED", request.reason());
            return new Cancelled(request.encounterId(), fence);
        });
    }
    public void releaseCancelledReservation(UUID actor, UUID practice, Cancelled confirmed) {
        if (confirmed.fence() > 0) reservations.release(actor, practice, confirmed.encounterId(), confirmed.fence());
    }
    public String creationHash(Context c, UUID patient, UUID key, Create request) {
        return ClinicalJson.hash(Map.of("patient", patient, "site", c.siteId(), "key", key, "request", request));
    }
    public Encounter get(Context c, UUID patient, UUID id, boolean audit) {
        var patientRecord = patients.requirePatient(c, patient);
        var row = c.jdbc().sql("SELECT * FROM clinical_encounter WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id")
            .param("practice", c.practiceId()).param("patient", patient).param("id", id).query((rs, n) -> new Row(
                rs.getObject("site_id", UUID.class), rs.getObject("responsible_clinician_id", UUID.class), rs.getObject("release_id", UUID.class),
                rs.getString("episode_snapshot"), rs.getString("care_mode"), rs.getString("care_state"), rs.getString("note_state"),
                rs.getObject("clinical_time", OffsetDateTime.class), rs.getObject("received_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class),
                rs.getLong("version"), rs.getLong("response_revision"), rs.getObject("unsigned_note_owner", UUID.class), rs.getLong("reservation_fence"), rs.getObject("session_id", UUID.class), rs.getObject("queue_entry_id", UUID.class)))
            .optional().orElseThrow(() -> new ClinicalAccessException("ENCOUNTER_UNAVAILABLE", 404));
        if (!row.site().equals(c.siteId()) || !row.clinician().equals(c.actorId())) throw new ClinicalAccessException("ENCOUNTER_UNAVAILABLE", 404);
        var release = templates.release(c, row.release(), false);
        var revision = c.jdbc().sql("""
            SELECT answers::text, received_at FROM encounter_response_revision
            WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id AND revision = :revision
            """).param("practice", c.practiceId()).param("patient", patient).param("id", id).param("revision", row.revision())
            .query((rs, n) -> new Saved(Arrays.asList(ClinicalJson.read(rs.getString(1), Answer[].class)), rs.getObject(2, OffsetDateTime.class)))
            .optional().orElse(new Saved(List.of(), null));
        if (audit) journal.audit(c, id, "ENCOUNTER_READ", null);
        return new Encounter(id, patient, row.site(), row.clinician(), patientRecord, release,
            row.snapshot() == null ? null : ClinicalJson.read(row.snapshot(), PregnancyRecords.Snapshot.class), row.mode(), row.care(), row.note(),
            row.clinicalTime(), row.receivedAt(), row.endedAt(), row.version(), row.revision(), row.owner(), row.fence(), revision.answers(), revision.time(), row.sessionId(), row.queueEntryId());
    }
    /** Rebuild a complete authorized projection; bounded overflow fails closed, never implies no visits. */
    public void refreshPatientVisits(Context c) {
        lockClinician(c);
        var rows=c.jdbc().sql("SELECT patient_id,encounter_id,version,clinical_time,release_id FROM clinical_encounter WHERE practice_id=:practice AND site_id=:site AND responsible_clinician_id=:actor ORDER BY encounter_id LIMIT 10001")
            .param("practice",c.practiceId()).param("site",c.siteId()).param("actor",c.actorId())
            .query((rs,n)->new VisitSource(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getObject(4,OffsetDateTime.class),rs.getObject(5,UUID.class))).list();
        if(rows.size()>10000) throw new ClinicalAccessException("VISIT_PROJECTION_CAPACITY",503);
        var visible=new ArrayList<ClinicalPatientRegistry.VisitSummary>();
        var releaseNames=new HashMap<UUID,String>(); var denied=new HashSet<UUID>();
        for(var row:rows) {
            if(denied.contains(row.release())) continue;
            if(!releaseNames.containsKey(row.release())) {
                try { releaseNames.put(row.release(),templates.release(c,row.release(),false).definition().title()); }
                catch(ClinicalAccessException e) { if(e.status()!=403) throw e; denied.add(row.release());continue; }
            }
            visible.add(new ClinicalPatientRegistry.VisitSummary(row.patient(),row.encounter(),row.version(),row.time(),releaseNames.get(row.release())));
        }
        patients.replaceVisits(c,visible);
    }
    private record VisitSource(UUID patient,UUID encounter,long version,OffsetDateTime time,UUID release) {}
    public List<Encounter> list(Context c, UUID patient) {
        patients.requirePatient(c, patient);
        journal.audit(c, patient, "ENCOUNTER_HISTORY_READ", null);
        return c.jdbc().sql("""
            SELECT encounter_id FROM clinical_encounter WHERE practice_id = :practice AND patient_id = :patient
                AND site_id = :site AND responsible_clinician_id = :actor ORDER BY clinical_time DESC LIMIT 100
            """).param("practice", c.practiceId()).param("patient", patient).param("site", c.siteId()).param("actor", c.actorId())
            .query(UUID.class).list().stream().map(id -> get(c, patient, id, false)).toList();
    }
    public Encounter save(Context c, UUID patient, UUID id, UUID key, Save request) {
        get(c, patient, id, false);
        if (request.clientTime() == null || request.changes().size() > 6000) throw new ClinicalAccessException("INVALID_DRAFT", 422);
        return journal.execute(c, "save-encounter/" + id, key, request, Encounter.class, () -> {
            lockEncounter(c, patient, id);
            var current = get(c, patient, id, false);
            version(current, request.expectedVersion());
            if (current.noteState().equals("SIGNED")) throw new ClinicalAccessException("SIGNED_NOTE_IMMUTABLE", 409);
            var answers = new LinkedHashMap<AnswerValidator.Address, Answer>();
            for (var answer : current.answers()) answers.put(new AnswerValidator.Address(answer.fieldId(), answer.groupItemId()), answer);
            var definitions = new TemplateCompiler().validate(current.release().definition());
            var changed = new HashSet<AnswerValidator.Address>();
            for (var change : request.changes()) {
                var address = new AnswerValidator.Address(change.fieldId(), change.groupItemId());
                if (!changed.add(address)) throw new ClinicalAccessException("DUPLICATE_FIELD_CHANGE", 422);
                if (change.answer() == null) answers.remove(address);
                else {
                    if (!Objects.equals(change.fieldId(), change.answer().fieldId()) || !Objects.equals(change.groupItemId(), change.answer().groupItemId()))
                        throw new ClinicalAccessException("ANSWER_ADDRESS_MISMATCH", 422);
                    var previous = answers.get(address);
                    answers.put(address, reviewedClinicianEntry(previous, change.answer()));
                }
            }
            var changedValues = new HashSet<UUID>();
            for (var change : request.changes()) {
                var previous = current.answers().stream().filter(a -> Objects.equals(a.fieldId(), change.fieldId()) && Objects.equals(a.groupItemId(), change.groupItemId())).findFirst().orElse(null);
                if (previous == null || change.answer() == null || !Objects.equals(previous.value(), change.answer().value()) || previous.missingState() != change.answer().missingState()) changedValues.add(change.fieldId());
            }
            answers.replaceAll((address, answer) -> definitions.containsKey(answer.fieldId()) && dependsOn(definitions.get(answer.fieldId()).visibleWhen(), changedValues)
                ? new Answer(answer.fieldId(), answer.groupItemId(), answer.value(), answer.missingState(), answer.source(), answer.review(), null, null) : answer);
            var values = List.copyOf(answers.values());
            new AnswerValidator().validate(current.release().definition(), values, AnswerValidator.Stage.DRAFT);
            // References require an owning service check. Unsupported reference concepts cannot be persisted.
            var fields = new TemplateCompiler().validate(current.release().definition());
            for (var answer : values) {
                if (answer.value() != null && fields.get(answer.fieldId()).type() == TemplateDefinition.Widget.REFERENCE) {
                    if (!"pregnancy.episode".equals(fields.get(answer.fieldId()).concept())) throw new ClinicalAccessException("REFERENCE_CAPABILITY_UNAVAILABLE", 422);
                    var episode = pregnancies.snapshot(c, patient, answer.value().referenceId()).episode();
                    if (episode.version() != answer.value().referenceVersion()) throw new ClinicalAccessException("SOURCE_VERSION_CONFLICT", 409);
                }
                if ("RECORD_REFERENCE".equals(answer.source().kind())) {
                    // Until additional chart references exist, only a checked episode reference may be imported.
                    if (answer.value() == null || fields.get(answer.fieldId()).type() != TemplateDefinition.Widget.REFERENCE
                        || !Objects.equals(answer.source().recordId(), answer.value().referenceId())
                        || !Objects.equals(answer.source().version(), answer.value().referenceVersion()))
                        throw new ClinicalAccessException("SOURCE_REFERENCE_UNAVAILABLE", 422);
                }
            }
            var next = current.responseRevision() + 1;
            c.jdbc().sql("""
                INSERT INTO encounter_response_revision(practice_id, patient_id, encounter_id, release_id, revision, answers, actor_id, client_time)
                VALUES (:practice, :patient, :id, :release, :revision, CAST(:answers AS jsonb), :actor, :client)
                """).param("practice", c.practiceId()).param("patient", patient).param("id", id).param("release", current.release().releaseId())
                .param("revision", next).param("answers", ClinicalJson.write(values)).param("actor", c.actorId()).param("client", request.clientTime()).update();
            c.jdbc().sql("""
                UPDATE clinical_encounter SET response_revision = :revision, version = version + 1, note_state = 'DRAFT'
                WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id
                """).param("revision", next).param("practice", c.practiceId()).param("patient", patient).param("id", id).update();
            journal.audit(c, id, "ENCOUNTER_DRAFT_SAVED", null);
            patients.visitsChanged(c);
            return get(c, patient, id, false);
        });
    }
    public FollowUpReceipt followUp(Context c, UUID patient, UUID id, UUID key, FollowUp request) {
        get(c, patient, id, false);
        interventions.require(c,ph.epr.api.encountertemplates.EncounterInterventionLinks.Kind.FOLLOW_UP,InterventionAuthorization.Action.PREPARE);
        if (request.intentId() == null || request.dueDate() == null || request.instruction() == null || request.instruction().isBlank() || request.instruction().length() > 2000)
            throw new ClinicalAccessException("FOLLOW_UP_DETAILS_REQUIRED", 422);
        return journal.execute(c, "follow-up/" + id, key, request, FollowUpReceipt.class, () -> {
            lockEncounter(c, patient, id); var current = get(c, patient, id, false); version(current, request.expectedVersion());
            if(!current.careState().equals("IN_PROGRESS")||current.noteState().equals("SIGNED"))throw new ClinicalAccessException("NEW_FOLLOW_UP_ENCOUNTER_REQUIRED",409);
            c.jdbc().sql("INSERT INTO encounter_followup_intent(practice_id, patient_id, encounter_id, intent_id, owner_id, due_date, instruction) VALUES (:practice, :patient, :encounter, :id, :actor, :due, :instruction)")
                .param("practice", c.practiceId()).param("patient", patient).param("encounter", id).param("id", request.intentId()).param("actor", c.actorId())
                .param("due", request.dueDate()).param("instruction", request.instruction()).update();
            interventions.append(c,patient,id,new ph.epr.api.encountertemplates.EncounterInterventionLinks.Summary(
                ph.epr.api.encountertemplates.EncounterInterventionLinks.Kind.FOLLOW_UP,request.intentId(),0,"Follow-up task",List.of(request.instruction()),
                "REQUESTED","PENDING","NOT_STARTED","NOT_APPLICABLE",c.actorId(),request.dueDate(),OffsetDateTime.now(),null,null));
            journal.event(c, request.intentId(), "FOLLOW_UP_REQUESTED", c.actorId());
            journal.audit(c, request.intentId(), "OWNED_FOLLOW_UP_REQUESTED", null);
            return new FollowUpReceipt(request.intentId(), c.actorId(), "PENDING");
        });
    }
    public Encounter transition(Context c, UUID patient, UUID id, UUID key, Transition request) {
        get(c, patient, id, false);
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) throw new ClinicalAccessException("REASON_REQUIRED", 422);
        lockClinician(c);
        return journal.execute(c, "care-transition/" + id, key, request, Encounter.class, () -> {
            lockEncounter(c, patient, id);
            var current = get(c, patient, id, false); version(current, request.expectedVersion());
            boolean valid = "IN_PROGRESS".equals(current.careState()) && Set.of("PAUSED", "FINISHED").contains(request.target())
                || "PAUSED".equals(current.careState()) && Set.of("IN_PROGRESS", "FINISHED").contains(request.target());
            if (!valid) throw new ClinicalAccessException("INVALID_CARE_TRANSITION", 422);
            c.jdbc().sql("""
                UPDATE clinical_encounter SET care_state = :target, ended_at = CASE WHEN :target = 'FINISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    version = version + 1 WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id
                """).param("target", request.target()).param("practice", c.practiceId()).param("patient", patient).param("id", id).update();
            journal.audit(c, id, "CARE_" + request.target(), request.reason());
            if ("FINISHED".equals(request.target())) journal.event(c, id, "CARE_FINISHED", current.unsignedNoteOwner());
            patients.visitsChanged(c);
            return get(c, patient, id, false);
        });
    }
    /** Invoke only after the tenant transaction commits; replay/reconcile repairs a crash in this gap. */
    public void releaseFinishedReservation(UUID practice, Encounter confirmed) {
        if ("FINISHED".equals(confirmed.careState())) reservations.release(confirmed.responsibleClinicianId(), practice, confirmed.encounterId(), confirmed.reservationFence());
    }
    public void lockEncounter(Context c, UUID patient, UUID id) {
        c.jdbc().sql("SELECT encounter_id FROM clinical_encounter WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id FOR UPDATE")
            .param("practice", c.practiceId()).param("patient", patient).param("id", id).query(UUID.class).optional()
            .orElseThrow(() -> new ClinicalAccessException("ENCOUNTER_UNAVAILABLE", 404));
    }
    private boolean dependsOn(TemplateDefinition.Rule rule, Set<UUID> changed) {
        return rule != null && (changed.contains(rule.fieldId()) || rule.rules().stream().anyMatch(child -> dependsOn(child, changed)));
    }
    /** The saved revision supplies the authenticated author; imported and unchanged answers never inherit review. */
    static Answer reviewedClinicianEntry(Answer previous, Answer submitted) {
        if (submitted.source() == null || !"CLINICIAN_ENTERED".equals(submitted.source().kind())
            || submitted.source().recordId() != null || submitted.source().version() != null
            || previous != null && Objects.equals(previous.value(), submitted.value())
                && previous.missingState() == submitted.missingState()) return submitted;
        return new Answer(submitted.fieldId(), submitted.groupItemId(), submitted.value(), submitted.missingState(),
            submitted.source(), Answer.Review.REVIEWED, submitted.hiddenResolution(), submitted.resolutionReason());
    }
    private void lockClinician(Context c) { c.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", c.actorId() + "/active-care").query((rs, n) -> true).single(); }
    private void version(Encounter current, long expected) { if (current.version() != expected) throw new ClinicalAccessException("VERSION_CONFLICT", 409); }
    private record Saved(List<Answer> answers, OffsetDateTime time) {}
    private record Row(UUID site, UUID clinician, UUID release, String snapshot, String mode, String care, String note,
                       OffsetDateTime clinicalTime, OffsetDateTime receivedAt, OffsetDateTime endedAt, long version, long revision, UUID owner, long fence, UUID sessionId, UUID queueEntryId) {}
}
