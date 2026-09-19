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
import ph.epr.api.encountertemplates.domain.TemplateDefinition;

@Component
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.demo-seed", havingValue = "true")
class LocalObgynSeeder {
    private final SyntheticClinicalIdentity identities;
    private final SyntheticClinicalPractice practices;
    private final TenantTransactions tenants;
    private final JdbcTemplateRegistry templates;
    private final ClinicalPatientRegistry patients;
    private final String password;
    LocalObgynSeeder(SyntheticClinicalIdentity identities, SyntheticClinicalPractice practices, TenantTransactions tenants,
        JdbcTemplateRegistry templates, ClinicalPatientRegistry patients, @Value("${epr.clinical.demo-password:ClinicalDemo123!}") String password) {
        this.identities = identities; this.practices = practices; this.tenants = tenants; this.templates = templates; this.patients = patients; this.password = password;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void seed() throws Exception {
        var clinician = id("clinician"); var practice = id("practice"); var site = id("site");
        var username = "obgyn-demo@epr.test";
        identities.prepare(clinician, username, password);
        practices.prepare(clinician, practice, site, username);
        var actor = new AuthenticatedActor(username);
        for (var key : List.of("general.outpatient", "obgyn.prenatal.initial", "obgyn.prenatal.followup", "obgyn.gynecology.initial", "obgyn.gynecology.followup", "obgyn.postpartum.followup")) {
            TemplateDefinition definition;
            try (var stream = getClass().getResourceAsStream("/encounter-templates/synthetic/" + key + ".json")) {
                definition = ClinicalJson.read(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), TemplateDefinition.class);
            }
            tenants.execute(actor, practice, site, true, c -> {
                var draft = templates.saveDraft(c, id(key + "/create"), new JdbcTemplateRegistry.DraftCommand(id(key + "/draft"), -1, definition, "Provisional synthetic field pack"), true);
                var approved = templates.approve(c, draft.draftId(), id(key + "/approve"), new JdbcTemplateRegistry.Approval(draft.version(), draft.checksum(), "Engineering fixture; no clinical approval or real-patient issuance"));
                return templates.publish(c, draft.draftId(), id(key + "/publish"), new JdbcTemplateRegistry.Publication(id(key + "/release"), approved.version()));
            });
        }
        tenants.execute(actor, practice, site, false, c -> patients.register(c, id("patient/register"), new ClinicalPatientRegistry.Registration(
            id("patient"), "Sofia Dela Cruz · synthetic", LocalDate.of(1996, 3, 12), "REPORTED", true, false)));
    }
    private UUID id(String key) { return UUID.nameUUIDFromBytes(("epr-synthetic-obgyn/" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
