package ph.epr.api.clinicalchart.infrastructure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ph.epr.api.clinicalchart.EncounterVitals;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.platformoperations.TenantCommandJournal;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
@Profile("local")
class JdbcEncounterVitals implements EncounterVitals {
    private final TenantCommandJournal journal;
    JdbcEncounterVitals(TenantCommandJournal journal) { this.journal = journal; }

    @Override public boolean hasHistory(Context c, UUID patient, UUID encounter) {
        return c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM encounter_vital_set WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:encounter)")
            .param("p", c.practiceId()).param("patient", patient).param("encounter", encounter).query(Boolean.class).single();
    }

    @Override public List<VitalSet> list(Context c, UUID patient, UUID encounter) {
        requireEncounter(c, patient, encounter);
        var ids = c.jdbc().sql("""
            SELECT head.vital_set_id FROM encounter_vital_set head
            JOIN encounter_vital_revision revision ON revision.practice_id=head.practice_id
                AND revision.patient_id=head.patient_id AND revision.encounter_id=head.encounter_id
                AND revision.vital_set_id=head.vital_set_id AND revision.version=head.current_version
            WHERE head.practice_id=:practice AND head.patient_id=:patient AND head.encounter_id=:encounter
                AND head.removed_at IS NULL
            ORDER BY revision.measured_at DESC, head.vital_set_id LIMIT 101
            """).param("practice", c.practiceId()).param("patient", patient).param("encounter", encounter)
            .query(UUID.class).list();
        if (ids.size() > 100) throw new ClinicalAccessException("VITAL_SET_CAPACITY", 503);
        return ids.stream().map(id -> get(c, patient, encounter, id)).toList();
    }

    @Override public TimelinePage timeline(Context c, UUID patient, UUID encounter, int page) {
        requireEncounter(c, patient, encounter);
        if (page < 0 || page > 10000) throw new ClinicalAccessException("INVALID_VITAL_TIMELINE_PAGE", 422);
        var rows = c.jdbc().sql("""
            SELECT head.vital_set_id, head.encounter_id, revision.*
            FROM encounter_vital_set head
            JOIN clinical_encounter visit ON visit.practice_id=head.practice_id
                AND visit.patient_id=head.patient_id AND visit.encounter_id=head.encounter_id
            JOIN encounter_vital_revision revision ON revision.practice_id=head.practice_id
                AND revision.patient_id=head.patient_id AND revision.encounter_id=head.encounter_id
                AND revision.vital_set_id=head.vital_set_id AND revision.version=head.current_version
            WHERE head.practice_id=:practice AND head.patient_id=:patient AND head.removed_at IS NULL
                AND visit.site_id=:site AND visit.responsible_clinician_id=:actor AND visit.deleted_at IS NULL
            ORDER BY revision.measured_at DESC, head.vital_set_id DESC LIMIT 51 OFFSET :offset
            """).param("practice", c.practiceId()).param("patient", patient).param("site", c.siteId())
            .param("actor", c.actorId()).param("offset", page * 50)
            .query((r, n) -> new Reading(r.getObject("vital_set_id", UUID.class),
                r.getObject("encounter_id", UUID.class), new Revision(r.getLong("version"),
                    r.getObject("measured_at", OffsetDateTime.class), r.getString("source"),
                    new Values(r.getBigDecimal("systolic_mmhg"), r.getBigDecimal("diastolic_mmhg"),
                        r.getBigDecimal("pulse_bpm"), r.getBigDecimal("respiratory_rate_bpm"),
                        r.getBigDecimal("temperature_c"), r.getBigDecimal("oxygen_saturation_percent"),
                        r.getBigDecimal("weight_kg"), r.getBigDecimal("height_cm")),
                    r.getString("correction_reason"), r.getObject("recorded_by", UUID.class),
                    r.getObject("received_at", OffsetDateTime.class)))).list();
        return new TimelinePage(rows.subList(0, Math.min(rows.size(), 50)), page, rows.size() > 50);
    }

    @Override public VitalSet create(Context c, UUID patient, UUID encounter, UUID key, Create request) {
        requireEncounter(c, patient, encounter);
        validate(request.measuredAt(), request.source(), request.values(), null, false);
        if (request.vitalSetId() == null) throw new ClinicalAccessException("VITAL_SET_ID_REQUIRED", 422);
        return journal.execute(c, "create-vital-set/" + encounter, key, request, VitalSet.class, () -> {
            lockEncounter(c, patient, encounter);
            if (c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM encounter_vital_set WHERE practice_id=:p AND vital_set_id=:id)")
                .param("p", c.practiceId()).param("id", request.vitalSetId()).query(Boolean.class).single())
                throw new ClinicalAccessException("VITAL_SET_ID_CONFLICT", 409);
            c.jdbc().sql("""
                INSERT INTO encounter_vital_set(practice_id, patient_id, encounter_id, vital_set_id)
                VALUES (:p,:patient,:encounter,:id)
                """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
                .param("id", request.vitalSetId()).update();
            insert(c, patient, encounter, request.vitalSetId(), 1,
                request.measuredAt(), request.source(), request.values(), null);
            journal.audit(c, request.vitalSetId(), "ENCOUNTER_VITAL_SET_RECORDED", null);
            return get(c, patient, encounter, request.vitalSetId());
        });
    }

    @Override public VitalSet correct(Context c, UUID patient, UUID encounter, UUID id, UUID key, Correct request) {
        requireEncounter(c, patient, encounter);
        validate(request.measuredAt(), request.source(), request.values(), request.reason(), true);
        return journal.execute(c, "correct-vital-set/" + id, key, request, VitalSet.class, () -> {
            lockEncounter(c, patient, encounter);
            var version = c.jdbc().sql("""
                SELECT current_version FROM encounter_vital_set
                WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:encounter
                    AND vital_set_id=:id AND removed_at IS NULL FOR UPDATE
                """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
                .param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new ClinicalAccessException("VITAL_SET_UNAVAILABLE", 404));
            if (version != request.expectedVersion()) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            insert(c, patient, encounter, id, version + 1,
                request.measuredAt(), request.source(), request.values(), request.reason().trim());
            c.jdbc().sql("""
                UPDATE encounter_vital_set SET current_version=:version
                WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:encounter AND vital_set_id=:id
                """).param("version", version + 1).param("p", c.practiceId()).param("patient", patient)
                .param("encounter", encounter).param("id", id).update();
            journal.audit(c, id, "ENCOUNTER_VITAL_SET_CORRECTED", request.reason().trim());
            return get(c, patient, encounter, id);
        });
    }

    @Override public Removal remove(Context c, UUID patient, UUID encounter, UUID id, UUID key, Remove request) {
        requireEncounter(c, patient, encounter);
        return journal.execute(c, "remove-vital-set/" + id, key, request, Removal.class, () -> {
            if ("SIGNED".equals(lockEncounter(c, patient, encounter)))
                throw new ClinicalAccessException("SIGNED_NOTE_IMMUTABLE", 409);
            var version = c.jdbc().sql("""
                SELECT current_version FROM encounter_vital_set
                WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:encounter
                    AND vital_set_id=:id AND removed_at IS NULL FOR UPDATE
                """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
                .param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new ClinicalAccessException("VITAL_SET_UNAVAILABLE", 404));
            if (version != request.expectedVersion()) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            c.jdbc().sql("""
                UPDATE encounter_vital_set SET removed_at=CURRENT_TIMESTAMP,
                    removed_by=:actor, removal_reason='Entered in error'
                WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:encounter AND vital_set_id=:id
                """).param("actor", c.actorId()).param("p", c.practiceId()).param("patient", patient)
                .param("encounter", encounter).param("id", id).update();
            journal.audit(c, id, "ENCOUNTER_VITAL_SET_REMOVED", "Entered in error");
            return new Removal(id, true);
        });
    }

    private VitalSet get(Context c, UUID patient, UUID encounter, UUID id) {
        var history = c.jdbc().sql("""
            SELECT * FROM encounter_vital_revision
            WHERE practice_id=:p AND patient_id=:patient AND encounter_id=:encounter AND vital_set_id=:id
            ORDER BY version
            """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
            .param("id", id).query((r, n) -> new Revision(r.getLong("version"),
                r.getObject("measured_at", OffsetDateTime.class), r.getString("source"),
                new Values(r.getBigDecimal("systolic_mmhg"), r.getBigDecimal("diastolic_mmhg"),
                    r.getBigDecimal("pulse_bpm"), r.getBigDecimal("respiratory_rate_bpm"),
                    r.getBigDecimal("temperature_c"), r.getBigDecimal("oxygen_saturation_percent"),
                    r.getBigDecimal("weight_kg"), r.getBigDecimal("height_cm")),
                r.getString("correction_reason"), r.getObject("recorded_by", UUID.class),
                r.getObject("received_at", OffsetDateTime.class))).list();
        if (history.isEmpty()) throw new ClinicalAccessException("VITAL_SET_UNAVAILABLE", 404);
        return new VitalSet(id, history.getLast(), history);
    }

    private void requireEncounter(Context c, UUID patient, UUID encounter) {
        var allowed = c.jdbc().sql("""
            SELECT EXISTS(SELECT 1 FROM clinical_encounter WHERE practice_id=:p AND patient_id=:patient
                AND encounter_id=:encounter AND site_id=:site AND responsible_clinician_id=:actor AND deleted_at IS NULL)
            """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
            .param("site", c.siteId()).param("actor", c.actorId()).query(Boolean.class).single();
        if (!allowed) throw new ClinicalAccessException("ENCOUNTER_UNAVAILABLE", 404);
    }

    private String lockEncounter(Context c, UUID patient, UUID encounter) {
        return c.jdbc().sql("""
            SELECT note_state FROM clinical_encounter WHERE practice_id=:p AND patient_id=:patient
                AND encounter_id=:encounter AND site_id=:site AND responsible_clinician_id=:actor AND deleted_at IS NULL FOR UPDATE
            """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
            .param("site", c.siteId()).param("actor", c.actorId()).query(String.class).optional()
            .orElseThrow(() -> new ClinicalAccessException("ENCOUNTER_UNAVAILABLE", 404));
    }

    private void insert(Context c, UUID patient, UUID encounter, UUID id, long version,
                        OffsetDateTime measuredAt, String source, Values v, String reason) {
        c.jdbc().sql("""
            INSERT INTO encounter_vital_revision(practice_id,patient_id,encounter_id,vital_set_id,version,
                measured_at,source,systolic_mmhg,diastolic_mmhg,pulse_bpm,respiratory_rate_bpm,
                temperature_c,oxygen_saturation_percent,weight_kg,height_cm,correction_reason,recorded_by)
            VALUES (:p,:patient,:encounter,:id,:version,:measured,:source,:systolic,:diastolic,:pulse,:respiratory,
                :temperature,:oxygen,:weight,:height,:reason,:actor)
            """).param("p", c.practiceId()).param("patient", patient).param("encounter", encounter)
            .param("id", id).param("version", version).param("measured", measuredAt).param("source", source.trim())
            .param("systolic", v.systolicMmhg()).param("diastolic", v.diastolicMmhg())
            .param("pulse", v.pulseBpm()).param("respiratory", v.respiratoryRateBpm())
            .param("temperature", v.temperatureC()).param("oxygen", v.oxygenSaturationPercent())
            .param("weight", v.weightKg()).param("height", v.heightCm()).param("reason", reason)
            .param("actor", c.actorId()).update();
    }

    private void validate(OffsetDateTime measuredAt, String source, Values v, String reason, boolean correction) {
        if (measuredAt == null || source == null || source.isBlank() || source.length() > 200 || v == null
            || correction && (reason == null || reason.isBlank() || reason.length() > 500)
            || (v.systolicMmhg() == null) != (v.diastolicMmhg() == null))
            throw new ClinicalAccessException("INVALID_VITAL_SET", 422);
        var numbers = Arrays.asList(v.systolicMmhg(), v.diastolicMmhg(), v.pulseBpm(), v.respiratoryRateBpm(),
            v.temperatureC(), v.oxygenSaturationPercent(), v.weightKg(), v.heightCm());
        if (numbers.stream().allMatch(Objects::isNull) || numbers.stream().filter(Objects::nonNull).anyMatch(n ->
            n.signum() <= 0 || n.scale() > 2)
            || Arrays.asList(v.systolicMmhg(), v.diastolicMmhg(), v.pulseBpm(), v.respiratoryRateBpm()).stream()
                .filter(Objects::nonNull).anyMatch(n -> n.precision() - n.scale() > 5 || n.scale() > 1)
            || Arrays.asList(v.temperatureC(), v.oxygenSaturationPercent()).stream()
                .filter(Objects::nonNull).anyMatch(n -> n.precision() - n.scale() > 3)
            || Arrays.asList(v.weightKg(), v.heightCm()).stream()
                .filter(Objects::nonNull).anyMatch(n -> n.precision() - n.scale() > 5)
            || v.oxygenSaturationPercent() != null && v.oxygenSaturationPercent().compareTo(new BigDecimal("100")) > 0)
            throw new ClinicalAccessException("INVALID_VITAL_SET", 422);
    }
}
