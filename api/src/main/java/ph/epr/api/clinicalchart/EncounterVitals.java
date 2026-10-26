package ph.epr.api.clinicalchart;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ph.epr.api.platformoperations.TenantTransactions.Context;

/** Encounter-linked observations, independently committed from the note draft. */
public interface EncounterVitals {
    record Values(BigDecimal systolicMmhg, BigDecimal diastolicMmhg, BigDecimal pulseBpm,
                  BigDecimal respiratoryRateBpm, BigDecimal temperatureC,
                  BigDecimal oxygenSaturationPercent, BigDecimal weightKg, BigDecimal heightCm) {}
    record Revision(long version, OffsetDateTime measuredAt, String source, Values values,
                    String correctionReason, UUID recordedBy, OffsetDateTime receivedAt) {}
    record VitalSet(UUID vitalSetId, Revision current, List<Revision> history) {}
    record Create(UUID vitalSetId, OffsetDateTime measuredAt, String source, Values values) {}
    record Correct(long expectedVersion, OffsetDateTime measuredAt, String source,
                   Values values, String reason) {}
    List<VitalSet> list(Context context, UUID patientId, UUID encounterId);
    VitalSet create(Context context, UUID patientId, UUID encounterId, UUID key, Create request);
    VitalSet correct(Context context, UUID patientId, UUID encounterId, UUID vitalSetId, UUID key, Correct request);
}
