package ph.epr.api.patientregistry;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
import ph.epr.api.clinicalchart.PregnancyRecords;
import ph.epr.api.encountertemplates.domain.*;
import ph.epr.api.encountertemplates.infrastructure.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.patientregistry.ClinicalPatientRegistry;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.*;

@SpringBootTest(properties = {"epr.clinical.enabled=true", "spring.session.jdbc.cleanup-cron=-", "logging.level.root=WARN"})
@ActiveProfiles("local")
@Testcontainers
class PatientRegistrationTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path ARTIFACTS;
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("epr.clinical.artifact-directory", () -> ARTIFACTS.toString());
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
    @Autowired PatientRegistrationRecords registry;
    @Autowired ClinicalAuthorization authorization;
    @Autowired JdbcClient control;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PlatformUserDirectory users;
    @Autowired TenantTransactions tenants;
    @Autowired ClinicalPatientRegistry patients;
    UUID practice, site, clinician, patient;
    AuthenticatedActor actor;

    @BeforeEach void setup() {
        clinician = UUID.randomUUID();
        var username = clinician + "@synthetic.test";
        actor = new AuthenticatedActor(username);
        control.sql("""
            INSERT INTO platform_user_account(id, username, display_name, password_hash, role, enabled, created_at, updated_at)
            VALUES (:id, :username, 'Synthetic Clinician', 'unused-by-service-tests', 'CLINICIAN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """).param("id", clinician).param("username", username).update();
        var setup = provision(username);
        practice = setup[0]; site = setup[1];
        patient = UUID.randomUUID();
        run(c -> patients.register(c, UUID.randomUUID(), new ClinicalPatientRegistry.Registration(patient, "Synthetic Patient", LocalDate.of(1995, 2, 1), "REPORTED", true, false)));
    }
    UUID[] provision(String username) {
        var practiceId = UUID.randomUUID(); var siteId = UUID.randomUUID();
        var admin = users.findByUsername("superadmin").orElseThrow();
        provisioning.provision(admin, new TenantCreationRequest(practiceId, "t-" + practiceId.toString().substring(0, 8), "Synthetic Practice",
            "Synthetic Clinician", username, new InitialTenantSite(siteId, "Synthetic Site", "", "", "PH", "1300000000", "1300000000", "1380300000"), UUID.randomUUID()));
        control.sql("""
            INSERT INTO practice_role_assignment(practice_id, user_id, practice_role, enabled, created_at, updated_at)
            VALUES (:practice, :actor, 'CLINICIAN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """).param("practice", practiceId).param("actor", clinician).update();
        control.sql("INSERT INTO practice_clinical_capability(practice_id, enabled) VALUES (:practice, true)").param("practice", practiceId).update();
        control.sql("""
            INSERT INTO practice_clinician_site_grant(practice_id, user_id, site_id, enabled, reproductive_access, template_approver, reason, granted_by)
            VALUES (:practice, :actor, :site, true, true, true, 'Synthetic test authorization', :admin)
            """).param("practice", practiceId).param("actor", clinician).param("site", siteId).param("admin", admin.id()).update();
        return new UUID[]{practiceId, siteId};
    }
    <T> T run(Function<TenantTransactions.Context, T> work) { return tenants.execute(actor, practice, site, true, work); }

    PatientRegistrationRecords.PatientData data(String name, java.util.Map<String,Object> changes) {
        var values=new java.util.HashMap<String,Object>();
        for(var field: PatientRegistrationRecords.PatientData.class.getRecordComponents()) {
            if(field.getType()==String.class) values.put(field.getName(), "");
        }
        values.putAll(java.util.Map.of("firstName",name,"birthAccuracy","unknown","sex","Not recorded","communication","Not recorded","philhealthRole","Unknown","contributor","Unknown","informationSource","Not recorded","representativeAuthority","Not recorded","provisional",true,"pinUnavailable",true));
        values.put("birthDate",null);values.putAll(changes);
        return ClinicalJson.read(ClinicalJson.write(values),PatientRegistrationRecords.PatientData.class);
    }
    PatientRegistrationRecords.IdentifierChanges emptyIds() {
        var remove=new PatientRegistrationRecords.IdentifierOperation("REMOVE",null);
        return new PatientRegistrationRecords.IdentifierChanges(remove,remove,remove);
    }
    PatientRegistrationRecords.PatientRegistration command(String name) {
        return new PatientRegistrationRecords.PatientRegistration(UUID.randomUUID(),data(name,Map.of()),emptyIds(),null,true);
    }
    @Test void allSixSectionsRoundTripProtectedIdentifiersAndNoCareIsCreated() {
        var values=new HashMap<String,Object>();
        values.putAll(Map.of("middleName","Middle","lastName","Family","suffix","Jr.","birthAccuracy","estimated","birthDate","1990-01-01","sex","Female","phone","+63 917 555 1234","email","synthetic@example.test","language","Filipino","communication","In person"));
        values.putAll(Map.of("street","Synthetic street","barangay","Synthetic barangay","city","Makati","province","Metro Manila","postalCode","1200","philhealthRole","Dependent","contributor","Unknown","informationSource","Patient reported","principalName","Synthetic Principal","principalRelationship","Child"));
        values.putAll(Map.of("representativeName","Synthetic Representative","representativeRelationship","Parent","representativePhone","09175551235","representativeAuthority","Patient nominated","emergencyName","Synthetic Contact","emergencyRelationship","Sibling","emergencyPhone","09175551236","otherIdType","External MRN","otherIdIssuer","Synthetic Hospital","pinUnavailable",false));
        var data=data("Synthetic Complete",values);
        var ids=new PatientRegistrationRecords.IdentifierChanges(new PatientRegistrationRecords.IdentifierOperation("REPLACE","123456789012"),new PatientRegistrationRecords.IdentifierOperation("REPLACE","987654321012"),new PatientRegistrationRecords.IdentifierOperation("REPLACE","SYN-EXTERNAL-55"));
        var command=new PatientRegistrationRecords.PatientRegistration(UUID.randomUUID(),data,ids,null,true);var key=UUID.randomUUID();
        var saved=run(c->registry.create(c,key,command));
        assertThat(saved.data()).isEqualTo(data);
        assertThat((Object)run(c->registry.detail(c,command.patientId()))).isEqualTo(saved);
        assertThat((Object)run(c->registry.create(c,key,command))).isEqualTo(saved);
        assertThat(ClinicalJson.write(saved)).doesNotContain("123456789012","987654321012","SYN-EXTERNAL-55");
        assertThat(saved.identifiers().pin()).endsWith("9012");
        assertThat(run(c->c.jdbc().sql("SELECT protected_identifiers FROM clinical_patient WHERE patient_id=:patient").param("patient",command.patientId()).query(String.class).single()).toString()).doesNotContain("123456789012");
        assertThat(run(c->c.jdbc().sql("SELECT count(*) FROM clinical_encounter WHERE patient_id=:patient").param("patient",command.patientId()).query(Long.class).single()).longValue()).isZero();
        var keep=new PatientRegistrationRecords.IdentifierOperation("UNCHANGED",null);
        var update=new PatientRegistrationRecords.PatientEdit(saved.patient().version(),data,new PatientRegistrationRecords.IdentifierChanges(keep,keep,keep),null,"Confirmed patient-supplied contact details");
        var edited=run(c->registry.edit(c,command.patientId(),UUID.randomUUID(),update));
        assertThat(edited.identifiers()).isEqualTo(saved.identifiers());
        assertThat(edited.patient().version()).isEqualTo(1);
        assertThatThrownBy(()->run(c->registry.edit(c,command.patientId(),UUID.randomUUID(),update))).hasMessage("PATIENT_VERSION_CONFLICT");
        assertThatThrownBy(()->run(c->{c.jdbc().sql("DELETE FROM patient_demographic_revision").update();return null;})).hasMessageContaining("DELETE FROM patient_demographic_revision");
    }
    @Test void staleDuplicateReviewAndChangedPayloadCannotBypassHumanReview() {
        var first=command("Matching Synthetic Name");run(c->registry.create(c,UUID.randomUUID(),first));
        var second=command("Matching Synthetic Name");
        assertThatThrownBy(()->run(c->registry.create(c,UUID.randomUUID(),second))).hasMessage("DUPLICATE_REVIEW_REQUIRED");
        var review=run(c->registry.check(c,new PatientRegistrationRecords.DuplicateCheck(second.data(),null)));
        assertThat(review.candidates()).hasSize(1);
        run(c->registry.edit(c,first.patientId(),UUID.randomUUID(),new PatientRegistrationRecords.PatientEdit(0,first.data(),emptyIds(),null,"Confirm supplied provisional identity")));
        var reviewed=new PatientRegistrationRecords.PatientRegistration(second.patientId(),second.data(),second.identifiers(),review.reviewId(),true);
        assertThatThrownBy(()->run(c->registry.create(c,UUID.randomUUID(),reviewed))).hasMessage("DUPLICATE_REVIEW_STALE");
        var fresh=run(c->registry.check(c,new PatientRegistrationRecords.DuplicateCheck(second.data(),null)));
        var confirmed=new PatientRegistrationRecords.PatientRegistration(second.patientId(),second.data(),second.identifiers(),fresh.reviewId(),true);var key=UUID.randomUUID();
        var saved=run(c->registry.create(c,key,confirmed));assertThat(saved.patient().patientId()).isEqualTo(second.patientId());
        assertThatThrownBy(()->run(c->registry.create(c,key,new PatientRegistrationRecords.PatientRegistration(second.patientId(),data("Changed",Map.of()),emptyIds(),fresh.reviewId(),true)))).hasMessage("IDEMPOTENCY_PAYLOAD_CONFLICT");
    }
    @Test void parallelSameKeyCreatesOneRecordAndBlindDifferentKeysRequireReview() throws Exception {
        var command=command("Concurrent Synthetic");var key=UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->run(c->registry.create(c,key,command)));
            var second=executor.submit(()->run(c->registry.create(c,key,command)));
            assertThat(first.get().patient()).isEqualTo(second.get().patient());
        }
        var other=command("Concurrent Synthetic");
        assertThatThrownBy(()->run(c->registry.create(c,UUID.randomUUID(),other))).hasMessage("DUPLICATE_REVIEW_REQUIRED");
        assertThat(run(c->registry.directory(c,"Concurrent Synthetic","all","all","name",0,20,false)).total()).isEqualTo(1);
    }
    @Test void moreThanOneHundredPatientsAreSearchableAndDetailsDoNotDependOnTheFirstPage() {
        UUID last=null;
        for(int i=0;i<105;i++){var command=command("Pagination Synthetic "+String.format("%03d",i));run(c->registry.create(c,UUID.randomUUID(),command));last=command.patientId();}
        var finalId=last;
        assertThat(run(c->registry.directory(c,"Pagination Synthetic","all","all","name",5,20,false)).items()).hasSize(5);
        assertThat(run(c->registry.directory(c,"Pagination Synthetic 104","all","all","name",0,20,false)).items().getFirst().patient().patientId()).isEqualTo(finalId);
        assertThat(run(c->registry.detail(c,finalId)).patient().birthDate()).isNull();
        assertThat(run(c->registry.directory(c,"","all","all","name",0,20,false)).visitHistoryState()).isEqualTo("UNAVAILABLE");
        run(c->{registry.open(c,finalId,UUID.randomUUID(),null);return null;});
        assertThat(run(c->registry.directory(c,"","all","all","name",0,20,true)).items()).hasSize(1);
        run(c->{c.jdbc().sql("UPDATE patient_care_assignment SET enabled=false WHERE patient_id=:patient").param("patient",finalId).update();return null;});
        assertThat(run(c->registry.directory(c,"","all","all","name",0,20,true)).items()).isEmpty();
    }
    @Test void validationAndIdentifierOperationsRejectTamperingAndRemainAtomic() {
        var command=command("Synthetic invalid");
        var future=data("Synthetic invalid",Map.of("birthAccuracy","exact","birthDate",LocalDate.now(ZoneId.of("Asia/Manila")).plusDays(1).toString()));
        assertThatThrownBy(()->run(c->registry.create(c,UUID.randomUUID(),new PatientRegistrationRecords.PatientRegistration(command.patientId(),future,emptyIds(),null,true)))).hasMessage("INVALID_BIRTH_DATE");
        var masked=new PatientRegistrationRecords.IdentifierOperation("REPLACE","•••• 9012");
        assertThatThrownBy(()->run(c->registry.create(c,UUID.randomUUID(),new PatientRegistrationRecords.PatientRegistration(command.patientId(),command.data(),new PatientRegistrationRecords.IdentifierChanges(masked,masked,masked),null,true)))).hasMessage("INVALID_IDENTIFIER_VALUE");
        assertThat(run(c->registry.directory(c,"Synthetic invalid","all","all","name",0,20,false)).total()).isZero();
    }
    @Test void explicitCapabilitiesPracticeSiteCareAssignmentAndReplayAreReauthorized() {
        assertThatThrownBy(()->authorization.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.REGISTER)).hasMessage("PATIENT_OPERATION_DENIED");
        control.sql("UPDATE practice_clinician_site_grant SET patient_register=true WHERE practice_id=:practice AND user_id=:actor").param("practice",practice).param("actor",clinician).update();
        authorization.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.REGISTER);
        assertThatThrownBy(()->authorization.authorizePatient(new AuthenticatedActor("superadmin"),practice,site,ClinicalAuthorization.PatientOperation.REGISTER)).hasMessage("CLINICAL_ACCESS_DENIED");
        assertThatThrownBy(()->authorization.authorizePatient(actor,practice,UUID.randomUUID(),ClinicalAuthorization.PatientOperation.REGISTER)).hasMessage("CLINICAL_ACCESS_DENIED");
        var command=command("Isolated Synthetic");var key=UUID.randomUUID();run(c->registry.create(c,key,command));
        var other=provision(actor.username());
        assertThatThrownBy(()->tenants.execute(actor,other[0],other[1],false,c->registry.detail(c,command.patientId()))).hasMessage("PATIENT_UNAVAILABLE");
        run(c->{c.jdbc().sql("UPDATE patient_care_assignment SET enabled=false WHERE patient_id=:patient").param("patient",command.patientId()).update();return null;});
        assertThatThrownBy(()->run(c->registry.create(c,key,command))).hasMessage("PATIENT_UNAVAILABLE");
        control.sql("UPDATE platform_user_account SET role='PRACTICE_STAFF' WHERE id=:actor").param("actor",clinician).update();
        authorization.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.REGISTER);
        control.sql("UPDATE practice_role_assignment SET enabled=false WHERE practice_id=:practice AND user_id=:actor AND practice_role='CLINICIAN'").param("practice",practice).param("actor",clinician).update();
        assertThatThrownBy(()->authorization.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.REGISTER)).hasMessage("CLINICAL_ACCESS_DENIED");
        assertThatThrownBy(()->run(c->patients.requirePatient(c,patient))).hasMessage("CLINICAL_ACCESS_DENIED");
        control.sql("UPDATE practice_role_assignment SET enabled=true WHERE practice_id=:practice AND user_id=:actor AND practice_role='CLINICIAN'").param("practice",practice).param("actor",clinician).update();
        control.sql("UPDATE platform_user_account SET role='PROVIDER_SUPPORT' WHERE id=:actor").param("actor",clinician).update();
        assertThatThrownBy(()->authorization.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.REGISTER)).hasMessage("CLINICAL_ACCESS_DENIED");
        control.sql("UPDATE platform_user_account SET role='CLINICIAN' WHERE id=:actor").param("actor",clinician).update();
        control.sql("UPDATE practice_membership SET enabled=false WHERE practice_id=:practice AND user_id=:actor").param("practice",practice).param("actor",clinician).update();
        assertThatThrownBy(()->run(c->registry.detail(c,command.patientId()))).hasMessage("CLINICAL_ACCESS_DENIED");
    }
    @Test void secondClinicianCannotSearchCountReadEditOrReviewAnotherCliniciansPatient() {
        var other=UUID.randomUUID();var username=other+"@synthetic.test";
        control.sql("INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,created_at,updated_at) VALUES (:id,:name,'Other synthetic clinician','unused','CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("id",other).param("name",username).update();
        control.sql("INSERT INTO practice_membership(practice_id,user_id,enabled,created_at,updated_at) VALUES (:practice,:actor,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("practice",practice).param("actor",other).update();
        control.sql("INSERT INTO practice_role_assignment(practice_id,user_id,practice_role,enabled,created_at,updated_at) VALUES (:practice,:actor,'CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("practice",practice).param("actor",other).update();
        control.sql("INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,reason,granted_by,patient_directory_read,patient_detail_read,patient_register,patient_demographic_edit) VALUES (:practice,:actor,:site,true,true,'Synthetic test',:grantor,true,true,true,true)").param("practice",practice).param("actor",other).param("site",site).param("grantor",clinician).update();
        var otherActor=new AuthenticatedActor(username);
        authorization.authorizePatient(otherActor,practice,site,ClinicalAuthorization.PatientOperation.DIRECTORY_READ);
        assertThat(tenants.execute(otherActor,practice,site,false,c->registry.directory(c,"Synthetic Patient","all","all","name",0,20,false)).total()).isZero();
        assertThatThrownBy(()->tenants.execute(otherActor,practice,site,false,c->registry.detail(c,patient))).hasMessage("PATIENT_UNAVAILABLE");
        assertThatThrownBy(()->tenants.execute(otherActor,practice,site,false,c->registry.edit(c,patient,UUID.randomUUID(),new PatientRegistrationRecords.PatientEdit(0,data("Changed",Map.of()),emptyIds(),null,"Not authorized")))).hasMessage("PATIENT_UNAVAILABLE");
        assertThat(tenants.execute(otherActor,practice,site,false,c->registry.check(c,new PatientRegistrationRecords.DuplicateCheck(data("Synthetic Patient",Map.of()),null))).candidates()).isEmpty();
    }
    @Test void derivedVisitsDeduplicateAndUnavailableProjectionNeverMatchesNoVisits() {
        assertThatThrownBy(()->run(c->registry.directory(c,"","all","all","name",0,20,false,"never"))).hasMessage("VISIT_HISTORY_UNAVAILABLE");
        var encounter=UUID.randomUUID();var time=OffsetDateTime.parse("2026-09-18T16:30:00Z");
        var visit=new ClinicalPatientRegistry.VisitSummary(patient,encounter,2,time,"Synthetic consultation");
        run(c->{patients.replaceVisits(c,List.of(visit,visit));return null;});
        var directory=run(c->registry.directory(c,"","all","all","last-visit",0,20,false,"visited"));
        assertThat(directory.visitHistoryState()).isEqualTo("CURRENT");assertThat(directory.total()).isEqualTo(1);
        assertThat(directory.items().getFirst().lastVisit()).isEqualTo(time);
        assertThat(run(c->registry.directory(c,"","all","all","name",0,20,false,"never")).total()).isZero();
        run(c->{patients.visitsChanged(c);return null;});
        assertThat(run(c->registry.directory(c,"","all","all","name",0,20,false)).visitHistoryState()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(()->run(c->registry.directory(c,"","all","all","name",0,20,false,"never"))).hasMessage("VISIT_HISTORY_UNAVAILABLE");
    }
    @Test void expiryActorAndCandidateBindingsProtectDuplicateDecisions() {
        var first=command("Review Bound Synthetic");run(c->registry.create(c,UUID.randomUUID(),first));
        var review=run(c->registry.check(c,new PatientRegistrationRecords.DuplicateCheck(first.data(),null)));
        assertThatThrownBy(()->run(c->{registry.open(c,patient,UUID.randomUUID(),review.reviewId());return null;})).hasMessage("DUPLICATE_REVIEW_STALE");
        run(c->{c.jdbc().sql("UPDATE patient_duplicate_review SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE review_id=:review").param("review",review.reviewId()).update();return null;});
        assertThatThrownBy(()->run(c->registry.create(c,UUID.randomUUID(),new PatientRegistrationRecords.PatientRegistration(UUID.randomUUID(),first.data(),emptyIds(),review.reviewId(),true)))).hasMessage("DUPLICATE_REVIEW_STALE");
    }
}
