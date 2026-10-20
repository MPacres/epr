package ph.epr.api.clinicalchart;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ph.epr.api.clinicalchart.ClinicalLists.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.SourceKind;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.ClinicalJson;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.*;

@SpringBootTest(properties={"epr.clinical.enabled=true","spring.session.jdbc.cleanup-cron=-","logging.level.root=WARN"})
@ActiveProfiles("local")
@Testcontainers
class ClinicalListsTests {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.user",POSTGRES::getUsername);registry.add("spring.flyway.password",POSTGRES::getPassword);
        registry.add("epr.tenant-provisioning.admin-jdbc-url",POSTGRES::getJdbcUrl);registry.add("epr.tenant-provisioning.admin-username",POSTGRES::getUsername);registry.add("epr.tenant-provisioning.admin-password",POSTGRES::getPassword);
        registry.add("epr.tenant-provisioning.tenant-jdbc-url-template",()->"jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/{database}");
    }
    @Autowired JdbcClient control;@Autowired TenantProvisioningService provisioning;@Autowired PlatformUserDirectory users;
    @Autowired TenantTransactions tenants;@Autowired ClinicalPatientRegistry patients;@Autowired ClinicalLists lists;
    UUID clinician,practice,site,patient,encounter;AuthenticatedActor actor;

    @BeforeEach void setup(){
        clinician=UUID.randomUUID();var username=clinician+"@lists.test";actor=new AuthenticatedActor(username);
        control.sql("INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,created_at,updated_at) VALUES(:id,:username,'List Clinician','unused','CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("id",clinician).param("username",username).update();
        practice=UUID.randomUUID();site=UUID.randomUUID();var admin=users.findByUsername("superadmin").orElseThrow();
        provisioning.provision(admin,new TenantCreationRequest(practice,"l-"+practice.toString().substring(0,8),"List Practice","List Clinician",username,new InitialTenantSite(site,"List Site","","","PH","1300000000","1300000000","1380300000"),UUID.randomUUID()));
        control.sql("INSERT INTO practice_role_assignment(practice_id,user_id,practice_role,enabled,created_at,updated_at) VALUES(:p,:a,'CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("p",practice).param("a",clinician).update();
        control.sql("INSERT INTO practice_clinical_capability(practice_id,enabled) VALUES(:p,true)").param("p",practice).update();
        control.sql("INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,template_approver,reason,granted_by) VALUES(:p,:a,:s,true,false,false,'Clinical-list tests',:admin)").param("p",practice).param("a",clinician).param("s",site).param("admin",admin.id()).update();
        for(var kind:SourceKind.values()){
            control.sql("INSERT INTO practice_clinical_chart_capability(practice_id,source_kind,enabled) VALUES(:p,:kind,true)").param("p",practice).param("kind",kind.name()).update();
            control.sql("INSERT INTO practice_clinical_chart_grant(practice_id,user_id,site_id,source_kind,can_read,can_propose,can_write,can_review,reason) VALUES(:p,:a,:s,:kind,true,true,true,true,'Clinical-list tests')").param("p",practice).param("a",clinician).param("s",site).param("kind",kind.name()).update();
        }
        patient=UUID.randomUUID();run(c->patients.register(c,UUID.randomUUID(),new ClinicalPatientRegistry.Registration(patient,"Synthetic List Patient",LocalDate.of(1990,1,1),"REPORTED",true,false)));
        encounter=UUID.randomUUID();var draft=UUID.randomUUID();var release=UUID.randomUUID();var checksum="a".repeat(64);
        run(c->{
            c.jdbc().sql("INSERT INTO encounter_template_draft(practice_id,draft_id,definition,checksum,change_summary,created_by) VALUES(:p,:draft,CAST('{}' AS jsonb),:checksum,'Clinical-list test fixture',:actor)").param("p",practice).param("draft",draft).param("checksum",checksum).param("actor",clinician).update();
            c.jdbc().sql("INSERT INTO encounter_template_release(practice_id,release_id,draft_id,template_key,business_version,definition,checksum,approved_by,approved_at,approval_basis,published_by) VALUES(:p,:release,:draft,'clinical-list-test','1',CAST('{}' AS jsonb),:checksum,:actor,CURRENT_TIMESTAMP,'Clinical-list test fixture',:actor)").param("p",practice).param("release",release).param("draft",draft).param("checksum",checksum).param("actor",clinician).update();
            c.jdbc().sql("INSERT INTO clinical_encounter(practice_id,patient_id,encounter_id,site_id,responsible_clinician_id,release_id,release_checksum,care_mode,clinical_time,unsigned_note_owner,reservation_fence) VALUES(:p,:patient,:encounter,:site,:actor,:release,:checksum,'IN_PERSON',CURRENT_TIMESTAMP,:actor,1)").param("p",practice).param("patient",patient).param("encounter",encounter).param("site",site).param("actor",clinician).param("release",release).param("checksum",checksum).update();
            return null;
        });
    }
    <T>T run(Function<TenantTransactions.Context,T> work){return tenants.execute(actor,practice,site,false,work);}
    AllergyData allergyData(String display,Code code){return new AllergyData(display,code,"MEDICATION","ALLERGY","CONFIRMED","HIGH",null,"UNKNOWN",null,"Patient reported",null,null,OffsetDateTime.now(),null,List.of(new Reaction(UUID.randomUUID(),"Rash","MODERATE",null,null)));}
    MedicationUseData medicationData(){return new MedicationUseData("Synthetic ingredient B",new Code("urn:epr:synthetic:medication","SYN-MED-B","synthetic-1","Synthetic ingredient B"),null,"100 synthetic units","Synthetic tablet",BigDecimal.ONE,"tablet","oral","daily",false,null,null,"UNKNOWN","Patient reported",null,"TAKING_AS_REPORTED",null,null,null,OffsetDateTime.now(),null);}
    ProblemData problemData(){return new ProblemData("Essential hypertension",new Code("urn:epr:synthetic:problem","hypertension","2026-10","Essential hypertension"),"CONFIRMED",null,"UNKNOWN",null,"UNKNOWN",null,"Clinician entered",null,OffsetDateTime.now(),null);}

    @Test void explicitAbsenceKnownValuesAndStalenessRemainDistinct(){
        assertThat(run(c->lists.summary(c,patient)).allergies().state()).isEqualTo(ListState.NOT_REVIEWED);
        var none=run(c->lists.reviewAllergies(c,patient,UUID.randomUUID(),new ReviewCommand(UUID.randomUUID(),"REVIEWED_NONE_KNOWN","Reviewed synthetic history",false,OffsetDateTime.now())));
        assertThat(none.stale()).isFalse();assertThat(run(c->lists.summary(c,patient)).allergies().state()).isEqualTo(ListState.EMPTY_REVIEWED);
        var id=UUID.randomUUID();var created=run(c->lists.createAllergy(c,patient,UUID.randomUUID(),new CreateAllergy(id,allergyData("Synthetic ingredient A",new Code("urn:epr:synthetic:medication","SYN-MED-A","synthetic-1","Synthetic ingredient A")))));
        assertThat(created.version()).isZero();assertThat(run(c->lists.summary(c,patient)).allergies().state()).isEqualTo(ListState.STALE);
        assertThatThrownBy(()->run(c->lists.reviseAllergy(c,patient,id,UUID.randomUUID(),new ReviseAllergy(9,"Wrong version",created.data())))).hasMessage("VERSION_CONFLICT");
        var revised=run(c->lists.reviseAllergy(c,patient,id,UUID.randomUUID(),new ReviseAllergy(0,"Corrected source",created.data())));
        assertThat(revised.version()).isEqualTo(1);assertThat(revised.revisions()).hasSize(2);
        assertThatThrownBy(()->run(c->{c.jdbc().sql("DELETE FROM allergy_revision WHERE practice_id=:p").param("p",practice).update();return null;})).hasMessageContaining("Clinical history is immutable");
    }

    @Test void reconciliationIsExactReplayableAndSupportsCanonicalSafety(){
        var allergy=run(c->lists.createAllergy(c,patient,UUID.randomUUID(),new CreateAllergy(UUID.randomUUID(),allergyData("Synthetic ingredient A",new Code("urn:epr:synthetic:medication","SYN-MED-A","synthetic-1","Synthetic ingredient A")))));
        var medication=run(c->lists.createMedicationUse(c,patient,UUID.randomUUID(),new CreateMedicationUse(UUID.randomUUID(),medicationData())));
        var problem=run(c->lists.createProblem(c,patient,UUID.randomUUID(),new CreateProblem(UUID.randomUUID(),0,problemData())));
        var summary=run(c->lists.summary(c,patient));var generations=Map.of(SourceKind.ALLERGY,summary.allergies().generation(),SourceKind.MEDICATION_USE,summary.medications().generation(),SourceKind.PROBLEM,summary.problems().generation());
        var command=new Reconcile(UUID.randomUUID(),encounter,generations,List.of(),"Encounter reconciliation",true,OffsetDateTime.now());var key=UUID.randomUUID();
        var review=run(c->lists.reconcile(c,patient,key,command));var replay=run(c->lists.reconcile(c,patient,key,command));
        assertThat(replay.reviewId()).isEqualTo(review.reviewId());assertThat(review.items()).extracting(ReviewItem::sourceId).containsExactlyInAnyOrder(allergy.id(),medication.id(),problem.id());
        var safety=run(c->lists.requireCurrentSafety(c,patient,review.reviewId()));assertThat(safety.allergies()).hasSize(1);assertThat(safety.medications()).hasSize(1);
        var otherSite=UUID.randomUUID();
        run(c->{c.jdbc().sql("INSERT INTO practice_site(site_id,practice_id,name,country_code,region_psgc_code,province_area_psgc_code,locality_psgc_code,time_zone,created_at,updated_at) VALUES(:site,:p,'Other Site','PH','1300000000','1300000000','1380300000','Asia/Manila',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("site",otherSite).param("p",practice).update();return null;});
        control.sql("INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,template_approver,reason,granted_by) VALUES(:p,:a,:s,true,false,false,'Other-site test',:a)").param("p",practice).param("a",clinician).param("s",otherSite).update();
        for(var kind:SourceKind.values())control.sql("INSERT INTO practice_clinical_chart_grant(practice_id,user_id,site_id,source_kind,can_read,can_propose,can_write,can_review,reason) VALUES(:p,:a,:s,:kind,true,true,true,true,'Other-site test')").param("p",practice).param("a",clinician).param("s",otherSite).param("kind",kind.name()).update();
        assertThatThrownBy(()->tenants.execute(actor,practice,otherSite,false,c->lists.requireCurrentSafety(c,patient,review.reviewId()))).hasMessage("CLINICAL_LIST_REVIEW_CONTEXT_MISMATCH");
        run(c->lists.transitionMedicationUse(c,patient,medication.id(),UUID.randomUUID(),new Transition(0,"STOP","Patient stopped medicine")));
        assertThatThrownBy(()->run(c->lists.requireCurrentSafety(c,patient,review.reviewId()))).hasMessage("CLINICAL_LIST_REVIEW_STALE");
    }

    @Test void proposalDecisionAppliesAtomicallyAndReplaysItsExactPayload(){
        var create=new CreateAllergy(UUID.randomUUID(),allergyData("Amoxicillin",new Code("urn:epr:synthetic:allergen","amoxicillin","2026-10","Amoxicillin")));
        var payload=ClinicalJson.read(ClinicalJson.write(create),tools.jackson.databind.JsonNode.class);
        var proposal=run(c->lists.propose(c,patient,UUID.randomUUID(),new Propose(UUID.randomUUID(),SourceKind.ALLERGY,"ADD",null,payload,"Patient-submitted history")));
        var summary=run(c->lists.summary(c,patient));
        var decision=new ReconciliationDecision(proposal.proposalId(),SourceKind.ALLERGY,null,0,"ADD",null,"Clinician confirmed patient report");
        var command=new Reconcile(UUID.randomUUID(),encounter,Map.of(SourceKind.ALLERGY,summary.allergies().generation(),SourceKind.MEDICATION_USE,summary.medications().generation(),SourceKind.PROBLEM,summary.problems().generation()),List.of(decision),"Encounter reconciliation",true,OffsetDateTime.now());
        var key=UUID.randomUUID();var review=run(c->lists.reconcile(c,patient,key,command));var replay=run(c->lists.reconcile(c,patient,key,command));
        assertThat(replay.manifestHash()).isEqualTo(review.manifestHash());assertThat(replay.decisions()).containsExactly(decision);
        assertThat(run(c->lists.allergies(c,patient,"active",0,20)).items()).extracting(Allergy::id).containsExactly(create.id());
        assertThat(run(c->lists.proposals(c,patient)).getFirst().decision()).isEqualTo("ACCEPTED");
        assertThatThrownBy(()->run(c->{c.jdbc().sql("DELETE FROM clinical_reconciliation_decision WHERE practice_id=:p AND review_id=:review").param("p",practice).param("review",review.reviewId()).update();return null;})).hasMessageContaining("Clinical history is immutable");
    }

    @Test void duplicateProblemsWrongPatientAndReviewRevocationFailClosed(){
        run(c->lists.createProblem(c,patient,UUID.randomUUID(),new CreateProblem(UUID.randomUUID(),0,problemData())));
        assertThatThrownBy(()->run(c->lists.createProblem(c,patient,UUID.randomUUID(),new CreateProblem(UUID.randomUUID(),1,problemData())))).hasMessage("DUPLICATE_PROBLEM_REVIEW_REQUIRED");
        assertThatThrownBy(()->run(c->lists.summary(c,UUID.randomUUID()))).hasMessage("PATIENT_UNAVAILABLE");
        control.sql("UPDATE practice_clinical_chart_grant SET can_review=false WHERE practice_id=:p AND user_id=:a AND site_id=:s AND source_kind='PROBLEM'").param("p",practice).param("a",clinician).param("s",site).update();
        assertThatThrownBy(()->run(c->lists.reviewProblems(c,patient,UUID.randomUUID(),new ReviewCommand(UUID.randomUUID(),"REVIEWED_KNOWN","Review",false,OffsetDateTime.now())))).hasMessage("CLINICAL_CHART_ACCESS_DENIED");
    }
}
