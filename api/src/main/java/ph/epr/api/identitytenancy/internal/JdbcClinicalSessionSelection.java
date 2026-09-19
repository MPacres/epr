package ph.epr.api.identitytenancy.internal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import ph.epr.api.identitytenancy.*;

@Service
class JdbcClinicalSessionSelection implements ClinicalSessionSelection {
    private final JdbcClient jdbc;
    JdbcClinicalSessionSelection(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Selection current(UUID clinician) { scope(); return find(clinician).orElse(new Selection(null, null, -1)); }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Selection select(UUID clinician, UUID practice, UUID session, long expectedVersion) {
        scope();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", clinician + "/active-clinical-state").query((rs, n) -> true).single();
        var current = find(clinician).orElse(new Selection(null, null, -1));
        if (practice.equals(current.practiceId()) && session.equals(current.sessionId())) return current;
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM clinician_active_care_reservation WHERE clinician_id = :actor)")
            .param("actor", clinician).query(Boolean.class).single()) throw new ClinicalAccessException("ACTIVE_CARE_REQUIRES_RECONCILIATION", 409);
        if (current.version() != expectedVersion) throw new ClinicalAccessException("SESSION_SELECTION_CONFLICT", 409);
        jdbc.sql("""
            INSERT INTO clinician_session_selection(clinician_id, practice_id, session_id) VALUES (:actor, :practice, :session)
            ON CONFLICT (clinician_id) DO UPDATE SET practice_id = EXCLUDED.practice_id, session_id = EXCLUDED.session_id,
                version = clinician_session_selection.version + 1, selected_at = CURRENT_TIMESTAMP
            """).param("actor", clinician).param("practice", practice).param("session", session).update();
        return find(clinician).orElseThrow();
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireSelected(UUID clinician, UUID practice, UUID session) {
        scope(); var current = find(clinician);
        if (current.isEmpty() || !practice.equals(current.get().practiceId()) || !session.equals(current.get().sessionId()))
            throw new ClinicalAccessException("SESSION_ACTIVATION_REQUIRED", 409);
    }
    private Optional<Selection> find(UUID clinician) { return jdbc.sql("SELECT practice_id, session_id, version FROM clinician_session_selection WHERE clinician_id = :actor")
        .param("actor", clinician).query((rs, n) -> new Selection(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3))).optional(); }
    private void scope() { jdbc.sql("SELECT set_config('epr.platform_operations', 'true', true)").query(String.class).single(); }
}
