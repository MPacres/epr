package ph.epr.api.identitytenancy.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.*;

@Service
class JdbcActiveCareReservations implements ActiveCareReservations {
    private final JdbcClient jdbc;
    JdbcActiveCareReservations(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserve(UUID clinician, UUID practice, UUID encounter, String requestHash) {
        scope();
        lock(clinician);
        return reserveLocked(clinician, practice, encounter, requestHash);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserveQueued(UUID clinician, UUID practice, UUID encounter, String requestHash, UUID session) {
        scope(); lock(clinician);
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM clinician_session_selection WHERE clinician_id = :actor AND practice_id = :practice AND session_id = :session)")
            .param("actor", clinician).param("practice", practice).param("session", session).query(Boolean.class).single())
            throw new ClinicalAccessException("SESSION_ACTIVATION_REQUIRED", 409);
        return reserveLocked(clinician, practice, encounter, requestHash);
    }
    private long reserveLocked(UUID clinician, UUID practice, UUID encounter, String requestHash) {
        jdbc.sql("""
            INSERT INTO clinician_active_care_reservation(clinician_id, practice_id, encounter_id, request_hash)
            VALUES (:actor, :practice, :encounter, :hash) ON CONFLICT (clinician_id) DO NOTHING
            """).param("actor", clinician).param("practice", practice).param("encounter", encounter).param("hash", requestHash).update();
        var reservation = jdbc.sql("SELECT * FROM clinician_active_care_reservation WHERE clinician_id = :actor FOR UPDATE")
            .param("actor", clinician).query((rs, n) -> new Reservation(rs.getObject("practice_id", UUID.class),
                rs.getObject("encounter_id", UUID.class), rs.getString("request_hash"), rs.getLong("fence"))).single();
        if (!reservation.practice().equals(practice) || !reservation.encounter().equals(encounter)
            || !reservation.hash().equals(requestHash)) throw new ClinicalAccessException("ACTIVE_CARE_REQUIRES_RECONCILIATION", 409);
        return reservation.fence();
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.OptionalLong fence(UUID clinician, UUID practice, UUID encounter) {
        scope();
        var value = jdbc.sql("SELECT fence FROM clinician_active_care_reservation WHERE clinician_id = :actor AND practice_id = :practice AND encounter_id = :encounter")
            .param("actor", clinician).param("practice", practice).param("encounter", encounter).query(Long.class).optional();
        return value.isPresent() ? java.util.OptionalLong.of(value.get()) : java.util.OptionalLong.empty();
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID clinician, UUID practice, UUID encounter, long fence) {
        scope();
        jdbc.sql("DELETE FROM clinician_active_care_reservation WHERE clinician_id = :actor AND practice_id = :practice AND encounter_id = :encounter AND fence = :fence")
            .param("actor", clinician).param("practice", practice).param("encounter", encounter).param("fence", fence).update();
    }
    private void lock(UUID clinician) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", clinician + "/active-clinical-state").query((rs, n) -> true).single();
    }
    private void scope() { jdbc.sql("SELECT set_config('epr.platform_operations', 'true', true)").query(String.class).single(); }
    private record Reservation(UUID practice, UUID encounter, String hash, long fence) {}
}
