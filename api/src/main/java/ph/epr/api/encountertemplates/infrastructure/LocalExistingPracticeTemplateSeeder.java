package ph.epr.api.encountertemplates.infrastructure;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ph.epr.api.identitytenancy.AuthenticatedActor;

/**
 * Explicit local-only bootstrap for adding the synthetic template pack to an existing Practice site.
 * The configured actor must already hold enabled template-approver access at that exact site.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.template-pack-seed.enabled", havingValue = "true")
class LocalExistingPracticeTemplateSeeder {
    private static final Logger LOG = LoggerFactory.getLogger(LocalExistingPracticeTemplateSeeder.class);

    private final LocalSyntheticTemplatePack pack;
    private final UUID practice;
    private final UUID site;
    private final String username;
    private final boolean exitAfterSeed;
    private final ConfigurableApplicationContext application;

    LocalExistingPracticeTemplateSeeder(LocalSyntheticTemplatePack pack,
        @Value("${epr.clinical.template-pack-seed.practice-id}") UUID practice,
        @Value("${epr.clinical.template-pack-seed.site-id}") UUID site,
        @Value("${epr.clinical.template-pack-seed.username}") String username,
        @Value("${epr.clinical.template-pack-seed.exit-after-seed:false}") boolean exitAfterSeed,
        ConfigurableApplicationContext application) {
        this.pack = pack;
        this.practice = practice;
        this.site = site;
        this.username = username;
        this.exitAfterSeed = exitAfterSeed;
        this.application = application;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        var releases = pack.seed(new AuthenticatedActor(username), practice, site);
        LOG.info("Seeded {} synthetic encounter template releases ({} selectable) for Practice {} at site {}",
            releases.size(), releases.stream().filter(release -> !release.retired()).count(), practice, site);
        if (exitAfterSeed) SpringApplication.exit(application, () -> 0);
    }
}
