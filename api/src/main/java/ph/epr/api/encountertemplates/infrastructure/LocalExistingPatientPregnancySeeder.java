package ph.epr.api.encountertemplates.infrastructure;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ph.epr.api.identitytenancy.AuthenticatedActor;

/** Explicit one-shot local fixture for a selected, authorized synthetic patient. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.pregnancy-fixture-seed.enabled", havingValue = "true")
class LocalExistingPatientPregnancySeeder {
    private static final Logger LOG = LoggerFactory.getLogger(LocalExistingPatientPregnancySeeder.class);

    private final LocalSyntheticPregnancyFixtures fixtures;
    private final UUID practice;
    private final UUID site;
    private final UUID patient;
    private final String username;
    private final boolean exitAfterSeed;
    private final ConfigurableApplicationContext application;

    LocalExistingPatientPregnancySeeder(LocalSyntheticPregnancyFixtures fixtures,
        @Value("${epr.clinical.pregnancy-fixture-seed.practice-id}") UUID practice,
        @Value("${epr.clinical.pregnancy-fixture-seed.site-id}") UUID site,
        @Value("${epr.clinical.pregnancy-fixture-seed.patient-id}") UUID patient,
        @Value("${epr.clinical.pregnancy-fixture-seed.username}") String username,
        @Value("${epr.clinical.pregnancy-fixture-seed.exit-after-seed:false}") boolean exitAfterSeed,
        ConfigurableApplicationContext application) {
        this.fixtures = fixtures;
        this.practice = practice;
        this.site = site;
        this.patient = patient;
        this.username = username;
        this.exitAfterSeed = exitAfterSeed;
        this.application = application;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        int created = fixtures.seed(new AuthenticatedActor(username), practice, site, patient);
        LOG.info("Seeded synthetic pregnancy fixtures for patient {} in Practice {} at site {} ({} episodes created)",
            patient, practice, site, created);
        if (exitAfterSeed) SpringApplication.exit(application, () -> 0);
    }
}
