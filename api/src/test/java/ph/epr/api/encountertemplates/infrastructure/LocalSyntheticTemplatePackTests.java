package ph.epr.api.encountertemplates.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ph.epr.api.encountertemplates.domain.TemplateDefinition;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.*;

@SpringBootTest(properties = {"epr.clinical.enabled=true", "spring.session.jdbc.cleanup-cron=-", "logging.level.root=WARN"})
@ActiveProfiles("local")
@Testcontainers
class LocalSyntheticTemplatePackTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("epr.tenant-provisioning.admin-jdbc-url", POSTGRES::getJdbcUrl);
        r.add("epr.tenant-provisioning.admin-username", POSTGRES::getUsername);
        r.add("epr.tenant-provisioning.admin-password", POSTGRES::getPassword);
        r.add("epr.tenant-provisioning.tenant-jdbc-url-template", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/{database}");
    }
    @Autowired JdbcClient control;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PlatformUserDirectory users;
    @Autowired TenantTransactions tenants;
    @Autowired LocalSyntheticTemplatePack pack;
    @Autowired JdbcTemplateRegistry templates;
    UUID practice, site, clinician;
    AuthenticatedActor actor;

    @BeforeEach void setup() {
        clinician = UUID.randomUUID();
        actor = new AuthenticatedActor(clinician + "@synthetic.test");
        control.sql("""
            INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,created_at,updated_at)
            VALUES(:id,:username,'Synthetic template author','unused-by-service-tests','CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """).param("id",clinician).param("username",actor.username()).update();
        practice = UUID.randomUUID(); site = UUID.randomUUID();
        provisioning.provision(users.findByUsername("superadmin").orElseThrow(), new TenantCreationRequest(
            practice, "seed-" + practice.toString().substring(0,8), "Synthetic template Practice", "Synthetic template author", actor.username(),
            new InitialTenantSite(site,"Synthetic site","","","PH","1300000000","1300000000","1380300000"),UUID.randomUUID()));
        control.sql("""
            INSERT INTO practice_role_assignment(practice_id,user_id,practice_role,enabled,created_at,updated_at)
            VALUES(:p,:u,'CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """).param("p",practice).param("u",clinician).update();
        control.sql("INSERT INTO practice_clinical_capability(practice_id,enabled) VALUES(:p,true)").param("p",practice).update();
        grant(site,clinician);
    }
    void grant(UUID siteId, UUID user) {
        control.sql("""
            INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,template_approver,reason,granted_by)
            VALUES(:p,:u,:s,true,true,true,'Explicit synthetic seeder test',:u)
            """).param("p",practice).param("u",user).param("s",siteId).update();
    }
    long count(String table) {
        return tenants.execute(actor,practice,site,true,c -> c.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single());
    }
    @Test void seedsSixVersionedDefinitionsAndRepeatsWithoutWrites() {
        var first = pack.seed(actor,practice,site);
        assertThat(first).hasSize(6).extracting(r -> r.definition().encounterType()).containsExactlyInAnyOrder(
            "OUTPATIENT","PRENATAL_INITIAL","PRENATAL_FOLLOWUP","GYNECOLOGY_INITIAL","GYNECOLOGY_FOLLOWUP","POSTPARTUM_FOLLOWUP");
        assertThat(first).allSatisfy(release -> {
            assertThat(release.definition().syntheticOnly()).isTrue();
            assertThat(release.definition().businessVersion()).isEqualTo("1.0.0");
            assertThat(release.approvalBasis()).startsWith("SYNTHETIC ONLY:");
        });
        var audits = count("clinical_audit_event");
        assertThat(pack.seed(actor,practice,site)).isEqualTo(first);
        assertThat(count("encounter_template_release")).isEqualTo(6);
        assertThat(count("clinical_audit_event")).isEqualTo(audits);
    }
    @Test void concurrentRunsDoNotDuplicateThePack() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> pack.seed(actor,practice,site));
            var b = executor.submit(() -> pack.seed(actor,practice,site));
            assertThat(a.get()).isEqualTo(b.get());
        }
        assertThat(count("encounter_template_release")).isEqualTo(6);
        assertThat(count("encounter_template_draft")).isEqualTo(6);
    }
    @Test void secondSiteSharesReleasesAndDoesNotRestoreRetiredAvailability() {
        var first = pack.seed(actor,practice,site);
        var otherSite = UUID.randomUUID();
        // Site fixture belongs to platform operations; no production code crosses this boundary.
        tenants.execute(actor,practice,site,true,c -> c.jdbc().sql("""
            INSERT INTO practice_site SELECT :s,practice_id,'Second synthetic site',facility_name,contact_number,
                country_code,region_psgc_code,province_area_psgc_code,locality_psgc_code,time_zone,created_at,updated_at
            FROM practice_site WHERE site_id=:original
            """).param("s",otherSite).param("original",site).update());
        grant(otherSite,clinician);
        assertThat(pack.seed(actor,practice,otherSite)).isEqualTo(first);
        var release = first.getFirst();
        tenants.execute(actor,practice,otherSite,true,c -> templates.availability(c,release.releaseId(),UUID.randomUUID(),
            new JdbcTemplateRegistry.Availability(0,true,"Retirement must survive seeding")));
        assertThat(pack.seed(actor,practice,otherSite).getFirst().retired()).isTrue();
        assertThat(count("encounter_template_release")).isEqualTo(6);
        assertThat(count("encounter_template_availability")).isEqualTo(12);
        List<JdbcTemplateRegistry.Release> originalCatalog = tenants.execute(actor,practice,site,true,c -> templates.catalog(c,null,null));
        List<JdbcTemplateRegistry.Release> otherCatalog = tenants.execute(actor,practice,otherSite,true,c -> templates.catalog(c,null,null));
        assertThat(originalCatalog).hasSize(6);
        assertThat(otherCatalog).hasSize(5);
    }
    @Test void deniedApproverOrWrongPracticeCannotSeedOrReplay() {
        pack.seed(actor,practice,site);
        control.sql("UPDATE practice_clinician_site_grant SET template_approver=false WHERE practice_id=:p AND user_id=:u")
            .param("p",practice).param("u",clinician).update();
        assertThatThrownBy(() -> pack.seed(actor,practice,site)).isInstanceOf(ClinicalAccessException.class).hasMessage("CLINICAL_ACCESS_DENIED");
        assertThatThrownBy(() -> pack.seed(actor,UUID.randomUUID(),site)).isInstanceOf(ClinicalAccessException.class);
        assertThatThrownBy(() -> pack.seed(actor,practice,UUID.randomUUID())).isInstanceOf(ClinicalAccessException.class);
    }
    @Test void conflictingPublishedContentRollsBackTheWholePack() throws Exception {
        var key = "obgyn.postpartum.followup";
        TemplateDefinition original;
        try (var stream = getClass().getResourceAsStream("/encounter-templates/synthetic/" + key + ".json")) {
            original = ClinicalJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),TemplateDefinition.class);
        }
        var changed = new TemplateDefinition(original.schemaVersion(),original.ruleVersion(),original.rendererVersion(),
            original.extractionVersion(),original.terminologyVersion(),original.templateKey(),"Different published content",
            original.specialty(),original.encounterType(),original.businessVersion(),original.requiresPregnancyEpisode(),true,original.sections());
        var releaseId = UUID.nameUUIDFromBytes(("epr-synthetic-obgyn/" + key + "/release").getBytes(StandardCharsets.UTF_8));
        var existing = tenants.execute(actor,practice,site,true,c -> {
            var draft = templates.saveDraft(c,UUID.randomUUID(),new JdbcTemplateRegistry.DraftCommand(UUID.randomUUID(),-1,changed,"Conflict fixture"),true);
            var approved = templates.approve(c,draft.draftId(),UUID.randomUUID(),new JdbcTemplateRegistry.Approval(draft.version(),draft.checksum(),"Synthetic conflict test"));
            return templates.publish(c,draft.draftId(),UUID.randomUUID(),new JdbcTemplateRegistry.Publication(releaseId,approved.version()));
        });
        assertThatThrownBy(() -> pack.seed(actor,practice,site)).isInstanceOf(IllegalStateException.class).hasMessageContaining("publish a new version");
        assertThat(count("encounter_template_release")).isEqualTo(1);
        assertThat(count("encounter_template_draft")).isEqualTo(1);
        JdbcTemplateRegistry.Release preserved = tenants.execute(actor,practice,site,true,c -> templates.release(c,releaseId,false));
        assertThat(preserved).isEqualTo(existing);
    }
}
