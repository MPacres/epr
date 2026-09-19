package ph.epr.api.encountertemplates.infrastructure;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterContinuity;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Service
class JdbcEncounterContinuity implements EncounterContinuity {
    private final JdbcEncounterService encounters;
    JdbcEncounterContinuity(JdbcEncounterService encounters) { this.encounters = encounters; }
    public List<Obligation> obligations(Context c) {
        var result = new ArrayList<Obligation>();
        var rows = c.jdbc().sql("SELECT patient_id, encounter_id FROM clinical_encounter WHERE practice_id = :practice AND site_id = :site AND responsible_clinician_id = :actor ORDER BY received_at LIMIT 1000")
            .param("practice", c.practiceId()).param("site", c.siteId()).param("actor", c.actorId())
            .query((rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list();
        for (var row : rows) {
            var encounter = encounters.get(c, row[0], row[1], false);
            result.add(new Obligation(encounter.encounterId(), encounter.version(), encounter.patientId(), encounter.patient().displayName(), encounter.patient().recordNumber(), encounter.encounterId(), encounter.unsignedNoteOwner(),
                "UNSIGNED_NOTE", "Review and complete encounter documentation", null, !"SIGNED".equals(encounter.noteState())));
            result.addAll(c.jdbc().sql("SELECT * FROM encounter_followup_intent WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :encounter")
                .param("practice", c.practiceId()).param("patient", row[0]).param("encounter", row[1]).query((rs,n) -> new Obligation(rs.getObject("intent_id", UUID.class), 0,
                    row[0], encounter.patient().displayName(), encounter.patient().recordNumber(), row[1], rs.getObject("owner_id", UUID.class), "FOLLOW_UP", rs.getString("instruction"), rs.getObject("due_date", LocalDate.class), true)).list());
        }
        return List.copyOf(result);
    }
}
