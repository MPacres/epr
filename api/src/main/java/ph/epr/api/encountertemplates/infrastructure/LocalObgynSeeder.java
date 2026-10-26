package ph.epr.api.encountertemplates.infrastructure;

import java.util.*;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;

@Component
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.demo-seed", havingValue = "true")
class LocalObgynSeeder {
    private final SyntheticClinicalIdentity identities;
    private final SyntheticClinicalPractice practices;
    private final TenantTransactions tenants;
    private final LocalSyntheticTemplatePack templatePack;
    private final LocalSyntheticPregnancyFixtures pregnancyFixtures;
    private final ClinicalPatientRegistry patients;
    private final String password;
    private final boolean seedPatient;
    LocalObgynSeeder(SyntheticClinicalIdentity identities, SyntheticClinicalPractice practices, TenantTransactions tenants,
        LocalSyntheticTemplatePack templatePack, LocalSyntheticPregnancyFixtures pregnancyFixtures,
        ClinicalPatientRegistry patients, @Value("${epr.clinical.demo-password:ClinicalDemo123!}") String password,
        @Value("${epr.clinical.demo-patient-seed:false}") boolean seedPatient) {
        this.identities = identities; this.practices = practices; this.tenants = tenants; this.templatePack = templatePack;
        this.pregnancyFixtures = pregnancyFixtures; this.patients = patients; this.password = password; this.seedPatient = seedPatient;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void seed() throws Exception {
        var clinician = id("clinician"); var practice = id("practice"); var site = id("site");
        var username = "obgyn-demo@epr.test";
        identities.prepare(clinician, username, password);
        practices.prepare(clinician, practice, site, username);
        var actor = new AuthenticatedActor(username);
        templatePack.seed(actor, practice, site);
        if (!seedPatient) return;
        tenants.execute(actor, practice, site, false, c -> patients.register(c, id("patient/register"), new ClinicalPatientRegistry.Registration(
            id("patient"), "Sofia Dela Cruz · synthetic", LocalDate.of(1996, 3, 12), "REPORTED", true, false)));
        pregnancyFixtures.seed(actor, practice, site, id("patient"));
    }
    private UUID id(String key) { return UUID.nameUUIDFromBytes(("epr-synthetic-obgyn/" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
