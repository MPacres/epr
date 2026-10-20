package ph.epr.api.encountertemplates.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
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
import ph.epr.api.clinicalchart.PregnancyRecords;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.*;

@SpringBootTest(properties = {"epr.clinical.enabled=true", "spring.session.jdbc.cleanup-cron=-", "logging.level.root=WARN"})
@ActiveProfiles("local")
@Testcontainers
class LocalSyntheticPregnancyFixturesTests {
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
        r.add("epr.tenant-provisioning.tenant-jdbc-url-template", () ->
            "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/{database}");
    }

    @Autowired JdbcClient control;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PlatformUserDirectory users;
    @Autowired TenantTransactions tenants;
    @Autowired ClinicalPatientRegistry patients;
    @Autowired PregnancyRecords pregnancies;
    @Autowired LocalSyntheticPregnancyFixtures fixtures;
    UUID practice, site, clinician, patient;
    AuthenticatedActor actor;

    @BeforeEach void setup() {
        clinician = UUID.randomUUID();
        actor = new AuthenticatedActor(clinician + "@synthetic.test");
        control.sql("""
            INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,created_at,updated_at)
            VALUES(:id,:username,'Synthetic fixture clinician','unused-by-tests','CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """).param("id", clinician).param("username", actor.username()).update();
        practice = UUID.randomUUID(); site = UUID.randomUUID(); patient = UUID.randomUUID();
        provisioning.provision(users.findByUsername("superadmin").orElseThrow(), new TenantCreationRequest(
            practice, "fixture-" + practice.toString().substring(0, 8), "Synthetic fixture Practice", "Synthetic fixture clinician",
            actor.username(), new InitialTenantSite(site, "Synthetic site", "", "", "PH", "1300000000", "1300000000", "1380300000"), UUID.randomUUID()));
        control.sql("INSERT INTO practice_role_assignment(practice_id,user_id,practice_role,enabled,created_at,updated_at) VALUES(:p,:u,'CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
            .param("p", practice).param("u", clinician).update();
        control.sql("INSERT INTO practice_clinical_capability(practice_id,enabled) VALUES(:p,true)").param("p", practice).update();
        control.sql("""
            INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,template_approver,reason,granted_by)
            VALUES(:p,:u,:s,true,true,false,'Synthetic fixture test',:u)
            """).param("p", practice).param("u", clinician).param("s", site).update();
        tenants.execute(actor, practice, site, false, c -> patients.register(c, UUID.randomUUID(),
            new ClinicalPatientRegistry.Registration(patient, "Synthetic fixture patient", LocalDate.of(1996, 3, 12), "REPORTED", true, false)));
    }

    @Test void createsSourcedEpisodesAndRepeatsWithoutDuplicatingRecords() {
        assertThat(fixtures.seed(actor, practice, site, patient)).isEqualTo(2);
        var episodes = tenants.execute(actor, practice, site, false, c -> pregnancies.list(c, patient));
        assertThat(episodes).extracting(PregnancyRecords.Episode::status).containsExactlyInAnyOrder("ONGOING", "COMPLETED");
        var current = episodes.stream().filter(e -> e.status().equals("ONGOING")).findFirst().orElseThrow();
        assertThat(current.selectedDatingId()).isNull();
        var datingRecords = tenants.execute(actor, practice, site, false, c -> pregnancies.dating(c, patient, current.episodeId()));
        assertThat(datingRecords)
            .singleElement().satisfies(dating -> {
                assertThat(dating.estimatedDate()).isEqualTo(LocalDate.of(2027, 5, 18));
                assertThat(dating.reviewStatus()).isEqualTo("NOT_REVIEWED");
            });
        var historyRecords = tenants.execute(actor, practice, site, false, c -> pregnancies.history(c, patient));
        assertThat(historyRecords)
            .singleElement().satisfies(history -> assertThat(history.reviewStatus()).isEqualTo("NOT_REVIEWED"));
        assertThat(fixtures.seed(actor, practice, site, patient)).isZero();
        var repeatedEpisodes = tenants.execute(actor, practice, site, false, c -> pregnancies.list(c, patient));
        assertThat(repeatedEpisodes).hasSize(2);
    }

    @Test void requiresAuthorizedPatientAndReproductiveGrant() {
        assertThatThrownBy(() -> fixtures.seed(actor, practice, site, UUID.randomUUID()))
            .isInstanceOf(ClinicalAccessException.class).hasMessage("PATIENT_UNAVAILABLE");
        control.sql("UPDATE practice_clinician_site_grant SET reproductive_access=false WHERE practice_id=:p AND user_id=:u AND site_id=:s")
            .param("p", practice).param("u", clinician).param("s", site).update();
        assertThatThrownBy(() -> fixtures.seed(actor, practice, site, patient)).isInstanceOf(RuntimeException.class);
    }
}
