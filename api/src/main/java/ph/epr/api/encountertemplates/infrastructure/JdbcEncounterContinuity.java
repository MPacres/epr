package ph.epr.api.encountertemplates.infrastructure;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.EncounterContinuity;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Service
class JdbcEncounterContinuity implements EncounterContinuity {
    private final JdbcEncounterService encounters;
    private final ClinicalPatientRegistry patients;
    JdbcEncounterContinuity(JdbcEncounterService encounters, ClinicalPatientRegistry patients) { this.encounters = encounters; this.patients = patients; }
    public List<Obligation> obligations(Context c) {
        var result = new ArrayList<Obligation>();
        var rows = c.jdbc().sql("SELECT patient_id, encounter_id, version, unsigned_note_owner, deleted_at IS NOT NULL FROM clinical_encounter WHERE practice_id = :practice AND site_id = :site AND responsible_clinician_id = :actor ORDER BY deleted_at NULLS FIRST, received_at LIMIT 1000")
            .param("practice", c.practiceId()).param("site", c.siteId()).param("actor", c.actorId())
            .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getObject(4, UUID.class), rs.getBoolean(5))).list();
        for (var row : rows) {
            if (row.deleted()) {
                var patient = patients.requirePatient(c, row.patient());
                result.add(new Obligation(row.encounter(), row.version(), row.patient(), patient.displayName(), patient.recordNumber(),
                    row.encounter(), row.owner(), "UNSIGNED_NOTE", "Deleted draft encounter", null, false));
                continue;
            }
            var encounter = encounters.get(c, row.patient(), row.encounter(), false);
            result.add(new Obligation(encounter.encounterId(), encounter.version(), encounter.patientId(), encounter.patient().displayName(), encounter.patient().recordNumber(), encounter.encounterId(), encounter.unsignedNoteOwner(),
                "UNSIGNED_NOTE", "Review and complete encounter documentation", null, !"SIGNED".equals(encounter.noteState())));
            result.addAll(c.jdbc().sql("SELECT * FROM encounter_followup_intent WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :encounter")
                .param("practice", c.practiceId()).param("patient", row.patient()).param("encounter", row.encounter()).query((rs,n) -> new Obligation(rs.getObject("intent_id", UUID.class), 0,
                    row.patient(), encounter.patient().displayName(), encounter.patient().recordNumber(), row.encounter(), rs.getObject("owner_id", UUID.class), "FOLLOW_UP", rs.getString("instruction"), rs.getObject("due_date", LocalDate.class), true)).list());
        }
        return List.copyOf(result);
    }
    private record Row(UUID patient, UUID encounter, long version, UUID owner, boolean deleted) {}
}
