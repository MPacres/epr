package ph.epr.api.clinicalchart.infrastructure;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.clinicalchart.PregnancyRecords;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.TenantCommandJournal;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
class JdbcPregnancyRecords implements PregnancyRecords {
    private final ClinicalPatientRegistry patients;
    private final TenantCommandJournal journal;
    JdbcPregnancyRecords(ClinicalPatientRegistry patients, TenantCommandJournal journal) { this.patients = patients; this.journal = journal; }
    private void access(Context context, UUID patient) {
        patients.requirePatient(context, patient);
        if (!context.reproductiveAccess()) throw new ClinicalAccessException("REPRODUCTIVE_ACCESS_DENIED", 403);
    }
    public List<Episode> list(Context c, UUID patient) {
        access(c, patient);
        journal.audit(c, patient, "PREGNANCY_EPISODES_READ", null);
        return c.jdbc().sql("SELECT * FROM pregnancy_episode WHERE practice_id = :practice AND patient_id = :patient ORDER BY received_at DESC")
            .param("practice", c.practiceId()).param("patient", patient).query((rs, n) -> episode(rs)).list();
    }
    private Episode get(Context c, UUID patient, UUID episode, boolean lock) {
        access(c, patient);
        return c.jdbc().sql("SELECT * FROM pregnancy_episode WHERE practice_id = :practice AND patient_id = :patient AND episode_id = :episode" + (lock ? " FOR UPDATE" : ""))
            .param("practice", c.practiceId()).param("patient", patient).param("episode", episode)
            .query((rs, n) -> episode(rs)).optional().orElseThrow(() -> new ClinicalAccessException("EPISODE_UNAVAILABLE", 404));
    }
    public Episode create(Context c, UUID patient, UUID key, CreateEpisode request) {
        access(c, patient); text(request.source(), 500);
        if (request.episodeId() == null || request.status() == null || !Set.of("UNCERTAIN", "ONGOING", "COMPLETED").contains(request.status())) invalid();
        return journal.execute(c, "create-episode/" + patient, key, request, Episode.class, () -> {
            c.jdbc().sql("""
                INSERT INTO pregnancy_episode(practice_id, patient_id, episode_id, status, responsible_clinician_id, source, clinical_time, created_by)
                VALUES (:practice, :patient, :episode, :status, :actor, :source, :clinical, :actor)
                """).param("practice", c.practiceId()).param("patient", patient).param("episode", request.episodeId())
                .param("status", request.status()).param("actor", c.actorId()).param("source", request.source())
                .param("clinical", request.clinicalTime(), Types.TIMESTAMP_WITH_TIMEZONE).update();
            journal.audit(c, request.episodeId(), "PREGNANCY_EPISODE_CREATED", null);
            return get(c, patient, request.episodeId(), false);
        });
    }
    public List<DatingAssessment> dating(Context c, UUID patient, UUID episode) {
        get(c, patient, episode, false);
        return c.jdbc().sql("""
            SELECT * FROM pregnancy_dating_assessment WHERE practice_id = :practice AND patient_id = :patient AND episode_id = :episode ORDER BY received_at
            """).param("practice", c.practiceId()).param("patient", patient).param("episode", episode)
            .query((rs, n) -> new DatingAssessment(rs.getObject("assessment_id", UUID.class), rs.getObject("estimated_date", java.time.LocalDate.class),
                rs.getObject("source_date", java.time.LocalDate.class), rs.getString("source_precision"), rs.getString("method"), rs.getString("source"),
                rs.getString("review_status"), rs.getObject("created_by", UUID.class), rs.getObject("received_at", OffsetDateTime.class))).list();
    }
    public Episode addDating(Context c, UUID patient, UUID episode, UUID key, AddDating request) {
        get(c, patient, episode, false); text(request.method(), 120); text(request.source(), 500); review(request.reviewStatus());
        if (request.assessmentId() == null || request.sourcePrecision() == null || !Set.of("EXACT", "APPROXIMATE", "UNKNOWN").contains(request.sourcePrecision())
            || "EXACT".equals(request.sourcePrecision()) && request.sourceDate() == null) invalid();
        return journal.execute(c, "add-dating/" + episode, key, request, Episode.class, () -> {
            version(get(c, patient, episode, true), request.expectedVersion());
            c.jdbc().sql("""
                INSERT INTO pregnancy_dating_assessment(practice_id, patient_id, episode_id, assessment_id, estimated_date, source_date,
                    source_precision, method, source, review_status, created_by)
                VALUES (:practice, :patient, :episode, :id, :estimate, :date, :precision, :method, :source, :review, :actor)
                """).param("practice", c.practiceId()).param("patient", patient).param("episode", episode).param("id", request.assessmentId())
                .param("estimate", request.estimatedDate(), Types.DATE).param("date", request.sourceDate(), Types.DATE)
                .param("precision", request.sourcePrecision()).param("method", request.method()).param("source", request.source())
                .param("review", request.reviewStatus()).param("actor", c.actorId()).update();
            bump(c, patient, episode);
            journal.audit(c, episode, "DATING_EVIDENCE_APPENDED", null);
            return get(c, patient, episode, false);
        });
    }
    public Episode selectDating(Context c, UUID patient, UUID episode, UUID key, SelectDating request) {
        get(c, patient, episode, false); text(request.reason(), 500);
        return journal.execute(c, "select-dating/" + episode, key, request, Episode.class, () -> {
            version(get(c, patient, episode, true), request.expectedVersion());
            var selected = dating(c, patient, episode).stream().filter(d -> d.assessmentId().equals(request.assessmentId())).findFirst()
                .orElseThrow(() -> new ClinicalAccessException("DATING_UNAVAILABLE", 404));
            if (!"REVIEWED".equals(selected.reviewStatus())) throw new ClinicalAccessException("DATING_REVIEW_REQUIRED", 422);
            c.jdbc().sql("""
                UPDATE pregnancy_episode SET selected_dating_id = :id, version = version + 1
                WHERE practice_id = :practice AND patient_id = :patient AND episode_id = :episode
                """).param("id", request.assessmentId()).param("practice", c.practiceId()).param("patient", patient).param("episode", episode).update();
            c.jdbc().sql("""
                INSERT INTO pregnancy_dating_selection(practice_id, patient_id, episode_id, episode_version, assessment_id, reason, actor_id)
                VALUES (:practice, :patient, :episode, :version, :id, :reason, :actor)
                """).param("practice", c.practiceId()).param("patient", patient).param("episode", episode)
                .param("version", request.expectedVersion() + 1).param("id", request.assessmentId()).param("reason", request.reason()).param("actor", c.actorId()).update();
            journal.audit(c, episode, "DATING_BASIS_SELECTED", request.reason());
            return get(c, patient, episode, false);
        });
    }
    public Snapshot snapshot(Context c, UUID patient, UUID episode) {
        var value = get(c, patient, episode, true);
        var selected = value.selectedDatingId() == null ? null : dating(c, patient, episode).stream()
            .filter(d -> d.assessmentId().equals(value.selectedDatingId())).findFirst().orElseThrow();
        return new Snapshot(value, selected);
    }
    public List<History> history(Context c, UUID patient) {
        access(c, patient);
        return c.jdbc().sql("""
            SELECT DISTINCT ON (item_id) * FROM obstetric_history_revision
            WHERE practice_id = :practice AND patient_id = :patient ORDER BY item_id, version DESC
            """).param("practice", c.practiceId()).param("patient", patient).query((rs, n) -> new History(
                rs.getObject("item_id", UUID.class), rs.getLong("version"), rs.getString("account"), rs.getString("source"),
                rs.getString("review_status"), rs.getString("reason"), rs.getObject("actor_id", UUID.class), rs.getObject("received_at", OffsetDateTime.class))).list();
    }
    public History saveHistory(Context c, UUID patient, UUID key, SaveHistory request) {
        access(c, patient); text(request.account(), 4000); text(request.source(), 500); text(request.reason(), 500); review(request.reviewStatus());
        if (request.itemId() == null || request.expectedVersion() < -1) invalid();
        return journal.execute(c, "history/" + patient, key, request, History.class, () -> {
            c.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", patient + "/history/" + request.itemId()).query((rs, n) -> true).single();
            var current = history(c, patient).stream().filter(h -> h.itemId().equals(request.itemId())).findFirst();
            if (current.map(History::version).orElse(-1L) != request.expectedVersion()) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            c.jdbc().sql("""
                INSERT INTO obstetric_history_revision(practice_id, patient_id, item_id, version, account, source, review_status, reason, actor_id)
                VALUES (:practice, :patient, :item, :version, :account, :source, :review, :reason, :actor)
                """).param("practice", c.practiceId()).param("patient", patient).param("item", request.itemId()).param("version", request.expectedVersion() + 1)
                .param("account", request.account()).param("source", request.source()).param("review", request.reviewStatus()).param("reason", request.reason()).param("actor", c.actorId()).update();
            journal.audit(c, request.itemId(), "OBSTETRIC_HISTORY_RECORDED", request.reason());
            return history(c, patient).stream().filter(h -> h.itemId().equals(request.itemId())).findFirst().orElseThrow();
        });
    }
    private void bump(Context c, UUID patient, UUID episode) {
        c.jdbc().sql("UPDATE pregnancy_episode SET version = version + 1 WHERE practice_id = :practice AND patient_id = :patient AND episode_id = :episode")
            .param("practice", c.practiceId()).param("patient", patient).param("episode", episode).update();
    }
    private void version(Episode episode, long expected) { if (episode.version() != expected) throw new ClinicalAccessException("VERSION_CONFLICT", 409); }
    private static void text(String value, int max) { if (value == null || value.isBlank() || value.length() > max) invalid(); }
    private static void review(String value) { if (!"REVIEWED".equals(value) && !"NOT_REVIEWED".equals(value)) invalid(); }
    private static void invalid() { throw new ClinicalAccessException("INVALID_PREGNANCY_RECORD", 422); }
    private Episode episode(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Episode(rs.getObject("episode_id", UUID.class), rs.getString("status"), rs.getObject("responsible_clinician_id", UUID.class),
            rs.getString("source"), rs.getObject("clinical_time", OffsetDateTime.class), rs.getObject("selected_dating_id", UUID.class),
            rs.getLong("version"), rs.getObject("created_by", UUID.class), rs.getObject("received_at", OffsetDateTime.class));
    }
}
