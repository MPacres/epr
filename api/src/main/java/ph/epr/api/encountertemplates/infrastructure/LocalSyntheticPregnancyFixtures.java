package ph.epr.api.encountertemplates.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ph.epr.api.clinicalchart.PregnancyRecords;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.ClinicalJson;
import ph.epr.api.platformoperations.TenantTransactions;

/** Opt-in local fixtures for one already authorized synthetic patient. */
@Component
@Profile("local")
class LocalSyntheticPregnancyFixtures {
    private static final String RESOURCE = "/clinical-fixtures/synthetic-pregnancy.json";

    private final TenantTransactions tenants;
    private final ClinicalPatientRegistry patients;
    private final PregnancyRecords pregnancies;

    LocalSyntheticPregnancyFixtures(TenantTransactions tenants, ClinicalPatientRegistry patients, PregnancyRecords pregnancies) {
        this.tenants = tenants;
        this.patients = patients;
        this.pregnancies = pregnancies;
    }

    int seed(AuthenticatedActor actor, UUID practice, UUID site, UUID patient) {
        var fixture = fixture();
        return tenants.execute(actor, practice, site, false, context -> {
            patients.requireSyntheticPatient(context, patient);
            if (!context.reproductiveAccess()) throw new IllegalStateException("Synthetic pregnancy fixture requires reproductive access");
            context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", practice + "/synthetic-pregnancy/" + patient).query((rs, n) -> true).single();

            int created = 0;
            var existing = pregnancies.list(context, patient);
            for (var episode : fixture.episodes()) {
                UUID episodeId = id(practice, patient, "episode/" + episode.key());
                var prior = existing.stream().filter(value -> value.episodeId().equals(episodeId)).findFirst();
                if (prior.isPresent()) {
                    if (!prior.get().status().equals(episode.status()) || !prior.get().source().equals(episode.source()))
                        throw new IllegalStateException("Synthetic pregnancy fixture conflicts with an existing episode: " + episode.key());
                    if (episode.dating() != null && pregnancies.dating(context, patient, episodeId).stream()
                        .noneMatch(value -> value.assessmentId().equals(id(practice, patient, "dating/" + episode.key()))))
                        throw new IllegalStateException("Synthetic pregnancy fixture is missing dating evidence: " + episode.key());
                    continue;
                }
                var saved = pregnancies.create(context, patient, id(practice, patient, "create/" + episode.key()),
                    new PregnancyRecords.CreateEpisode(episodeId, episode.status(), episode.source(), null));
                if (episode.dating() != null) {
                    var dating = episode.dating();
                    pregnancies.addDating(context, patient, episodeId, id(practice, patient, "add-dating/" + episode.key()),
                        new PregnancyRecords.AddDating(id(practice, patient, "dating/" + episode.key()), saved.version(),
                            dating.estimatedDate(), dating.sourceDate(), dating.sourcePrecision(), dating.method(),
                            dating.source(), dating.reviewStatus()));
                }
                created++;
            }

            var history = pregnancies.history(context, patient);
            for (var item : fixture.history()) {
                UUID itemId = id(practice, patient, "history/" + item.key());
                var prior = history.stream().filter(value -> value.itemId().equals(itemId)).findFirst();
                if (prior.isPresent()) continue; // A later clinician correction must remain authoritative.
                pregnancies.saveHistory(context, patient, id(practice, patient, "save-history/" + item.key()),
                    new PregnancyRecords.SaveHistory(itemId, -1, item.account(), item.source(), item.reviewStatus(),
                        "Initial synthetic development fixture"));
            }
            return created;
        });
    }

    private Fixture fixture() {
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Synthetic pregnancy fixture is missing: " + RESOURCE);
            return ClinicalJson.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Fixture.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic pregnancy fixture could not be loaded", exception);
        }
    }

    private UUID id(UUID practice, UUID patient, String key) {
        return UUID.nameUUIDFromBytes(("epr-synthetic-pregnancy/" + practice + "/" + patient + "/" + key)
            .getBytes(StandardCharsets.UTF_8));
    }

    record Fixture(List<EpisodeFixture> episodes, List<HistoryFixture> history) {}
    record EpisodeFixture(String key, String status, String source, DatingFixture dating) {}
    record DatingFixture(LocalDate estimatedDate, LocalDate sourceDate, String sourcePrecision, String method,
                         String source, String reviewStatus) {}
    record HistoryFixture(String key, String account, String source, String reviewStatus) {}
}
