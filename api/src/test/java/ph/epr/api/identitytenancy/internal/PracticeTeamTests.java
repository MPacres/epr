package ph.epr.api.identitytenancy.internal;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
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
import ph.epr.api.identitytenancy.*;
import ph.epr.api.identitytenancy.PracticeTeam.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.*;
import ph.epr.api.practiceadministration.application.TeamAdministration;

@SpringBootTest(properties={"epr.team.enabled=true","epr.clinical.enabled=true","spring.session.jdbc.cleanup-cron=-","logging.level.root=WARN"})
@ActiveProfiles("local")
@Testcontainers
class PracticeTeamTests {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.flyway.user",POSTGRES::getUsername);r.add("spring.flyway.password",POSTGRES::getPassword);
        r.add("epr.tenant-provisioning.admin-jdbc-url",POSTGRES::getJdbcUrl);r.add("epr.tenant-provisioning.admin-username",POSTGRES::getUsername);r.add("epr.tenant-provisioning.admin-password",POSTGRES::getPassword);
        r.add("epr.tenant-provisioning.tenant-jdbc-url-template",()->"jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/{database}");
    }
    @Autowired PracticeTeam team;
    @Autowired TeamAdministration commands;
    @Autowired PracticeSiteDirectory sites;
    @Autowired PlatformUserDirectory users;
    @Autowired TenantProvisioningService provisioning;
    @Autowired ClinicalAuthorization clinical;
    @Autowired ClinicalChartAuthorization clinicalLists;
    @Autowired SyntheticClinicalCapability syntheticCapability;
    @Autowired ph.epr.api.platformoperations.application.TenantSchemaUpgradeService schemaUpgrades;
    @Autowired JdbcClient jdbc;
    UUID practice,site,admin;
    AuthenticatedActor actor;
    @BeforeEach void setup(){
        practice=UUID.randomUUID();site=UUID.randomUUID();String email=practice+"@synthetic.test";
        var superadmin=users.findByUsername("superadmin").orElseThrow();
        provisioning.provision(superadmin,new TenantCreationRequest(practice,"t-"+practice.toString().substring(0,8),"Synthetic Team Practice","Synthetic Administrator",email,
            new InitialTenantSite(site,"Synthetic Site","","","PH","1300000000","1300000000","1380300000"),UUID.randomUUID()));
        admin=users.findByUsername(email).orElseThrow().id();
        users.completeRequiredPasswordChange(admin,"PrivateSyntheticPassword123!",UUID.randomUUID(),OffsetDateTime.now());
        actor=new AuthenticatedActor(email);
    }
    TeamCreate create(String email,List<TeamRole> roles,List<TeamSiteGrant> grants){return new TeamCreate(email,"Synthetic Team Member",null,roles,grants,"Synthetic test setup");}
    TeamResult staff(){return commands.create(actor,practice,UUID.randomUUID(),create(UUID.randomUUID()+"@synthetic.test",List.of(TeamRole.SECRETARY),List.of()));}
    @Test void scopesAndSitesDoNotGiveAnAdministratorClinicalAuthority(){
        assertThat(team.scopes(actor)).extracting(TeamScope::practiceId).containsExactly(practice);
        assertThat(sites.sites(actor,practice)).extracting(PracticeSiteDirectory.TeamSite::siteId).containsExactly(site);
        assertThatThrownBy(()->clinical.authorize(actor,practice,site,false)).isInstanceOf(ClinicalAccessException.class);
        var outsider=new AuthenticatedActor("superadmin");
        assertThat(team.scopes(outsider)).isEmpty();
        assertThatThrownBy(()->team.members(outsider,practice,"","ALL","ALL",null,0,25)).isInstanceOf(TeamException.class);
        assertThatThrownBy(()->sites.sites(actor,UUID.randomUUID())).isInstanceOf(TeamException.class);
        assertThatThrownBy(()->team.events(outsider,practice,admin,0)).isInstanceOf(TeamException.class);
        assertThatThrownBy(()->team.resolve(outsider,practice,"someone@synthetic.test")).isInstanceOf(TeamException.class);
    }
    @Test void syntheticCapabilityRequiresRolloutAndAnExplicitClinicianGrantAndAuditsTheCommand(){
        var provider=users.findByUsername("superadmin").orElseThrow();
        jdbc.sql("UPDATE practice_tenant_registry SET schema_version='2' WHERE practice_id=:p")
            .param("p",practice).update();
        var version=jdbc.sql("SELECT version FROM practice_tenant_registry WHERE practice_id=:p")
            .param("p",practice).query(Long.class).single();
        var key=UUID.randomUUID();
        assertThatThrownBy(()->syntheticCapability.enable(provider,practice,key,version,"Synthetic clinical test activation"))
            .isInstanceOf(ClinicalAccessException.class).hasMessage("CLINICAL_SCHEMA_NOT_READY");
        schemaUpgrades.upgrade(provider,practice,UUID.randomUUID(),version,"Synthetic test schema rollout");
        var readyVersion=jdbc.sql("SELECT version FROM practice_tenant_registry WHERE practice_id=:p")
            .param("p",practice).query(Long.class).single();
        assertThatThrownBy(()->syntheticCapability.enable(provider,practice,key,readyVersion,"Synthetic clinical test activation"))
            .isInstanceOf(ClinicalAccessException.class).hasMessage("CLINICIAN_SITE_GRANT_REQUIRED");
        var member=team.member(actor,practice,admin);
        commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(member.version(),true,
            List.of(TeamRole.PRACTICE_ADMINISTRATOR,TeamRole.CLINICIAN),List.of(new TeamSiteGrant(site,false)),"Synthetic test doctor grant"));
        assertThatThrownBy(()->syntheticCapability.enable(users.findByUsername(actor.username()).orElseThrow(),practice,key,
            readyVersion,"Synthetic clinical test activation")).isInstanceOf(ClinicalAccessException.class).hasMessage("CLINICAL_ENABLEMENT_DENIED");
        var result=syntheticCapability.enable(provider,practice,key,readyVersion,"Synthetic clinical test activation");
        assertThat(result.enabled()).isTrue();
        assertThat(result.version()).isEqualTo(readyVersion+1);
        assertThat(result.replayed()).isFalse();
        assertThat(syntheticCapability.enable(provider,practice,key,readyVersion,"Synthetic clinical test activation").replayed()).isTrue();
        assertThatThrownBy(()->syntheticCapability.enable(provider,practice,key,readyVersion,"Different reason"))
            .isInstanceOf(ClinicalAccessException.class).hasMessage("IDEMPOTENCY_PAYLOAD_CONFLICT");
        assertThat(clinical.available(actor)).hasSize(1);
        clinical.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.DIRECTORY_READ);
        assertThat(jdbc.sql("SELECT source_kind FROM practice_clinical_chart_capability WHERE practice_id=:p AND enabled ORDER BY source_kind")
            .param("p",practice).query(String.class).list()).containsExactly("ALLERGY","MEDICATION_USE","PROBLEM");
        assertThat(jdbc.sql("SELECT count(*) FROM practice_clinical_chart_grant WHERE practice_id=:p")
            .param("p",practice).query(Long.class).single()).isZero();
        assertThat(team.scopes(actor).getFirst().clinicalReadiness().state()).isEqualTo(ClinicalReadiness.State.READY);
        assertThat(jdbc.sql("SELECT count(*) FROM practice_administration_event WHERE practice_id=:p AND event_type='SYNTHETIC_CLINICAL_ENABLED'")
            .param("p",practice).query(Long.class).single()).isEqualTo(1);
    }
    @Test void createsOnboardsAndReplaysWithoutStoringCredentials(){
        String email=UUID.randomUUID()+"@synthetic.test";UUID key=UUID.randomUUID();var command=create(email,List.of(TeamRole.SECRETARY),List.of());
        var first=commands.create(actor,practice,key,command);var replay=commands.create(actor,practice,key,command);
        assertThat(first.temporaryPassword()).isNotBlank();assertThat(replay.temporaryPassword()).isNull();assertThat(replay.replayed()).isTrue();
        assertThat(first.member().userId()).isEqualTo(replay.member().userId());
        assertThat(team.members(actor,practice,"Synthetic Team Member","SECRETARY","ENABLED",null,0,25).total()).isEqualTo(1);
        assertThatThrownBy(()->commands.create(actor,practice,key,create(email,List.of(TeamRole.NURSE),List.of()))).isInstanceOf(TeamException.class).hasMessageContaining("retry key");
        var passwordKey=UUID.randomUUID();
        users.completeRequiredPasswordChange(first.member().userId(),"DoctorPrivatePassword123!",passwordKey,OffsetDateTime.now());
        assertThat(users.completeRequiredPasswordChange(first.member().userId(),"DoctorPrivatePassword123!",passwordKey,OffsetDateTime.now()).passwordResetRequired()).isFalse();
        assertThat(team.member(actor,practice,first.member().userId()).canReissue()).isFalse();
        assertThatThrownBy(()->team.reissue(actor,practice,first.member().userId(),UUID.randomUUID(),new TeamPassword(first.member().version(),"Recovery attempt"))).isInstanceOf(TeamException.class);
        assertThat(jdbc.sql("SELECT result::text FROM practice_member_command WHERE practice_id=:p").param("p",practice).query(String.class).list()).allSatisfy(value->assertThat(value).doesNotContain(first.temporaryPassword()));
    }
    @Test void reissueRotatesOnlyUnclaimedCredentialsAndDoesNotReplayThem(){
        var created=staff();var id=created.member().userId();var key=UUID.randomUUID();var cmd=new TeamPassword(created.member().version(),"Original delivery was lost");
        var issue=team.reissue(actor,practice,id,key,cmd);
        assertThat(issue.temporaryPassword()).isNotEqualTo(created.temporaryPassword());
        assertThat(team.reissue(actor,practice,id,key,cmd).temporaryPassword()).isNull();
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        assertThat(encoder.matches(created.temporaryPassword(),users.findByUsername(created.member().username()).orElseThrow().passwordHash())).isFalse();
        assertThat(encoder.matches(issue.temporaryPassword(),users.findByUsername(created.member().username()).orElseThrow().passwordHash())).isTrue();
    }
    @Test void linksConfirmedExistingAccountWithoutChangingIdentityOrOtherMemberships(){
        UUID other=UUID.randomUUID();String email=other+"@synthetic.test";
        jdbc.sql("INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,created_at,updated_at) VALUES(:id,:email,'Synthetic Team Member','original-hash','PRACTICE_STAFF',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("id",other).param("email",email).update();
        var resolution=team.resolve(actor,practice,email.toUpperCase());assertThat(resolution.userId()).isEqualTo(other);
        assertThatThrownBy(()->commands.create(actor,practice,UUID.randomUUID(),create(email,List.of(TeamRole.NURSE),List.of()))).isInstanceOf(TeamException.class);
        commands.create(actor,practice,UUID.randomUUID(),new TeamCreate(email,"Synthetic Team Member",other,List.of(TeamRole.NURSE),List.of(),"Verified person"));
        assertThat(users.findByUsername(email).orElseThrow().passwordHash()).isEqualTo("original-hash");
        assertThatThrownBy(()->commands.create(actor,practice,UUID.randomUUID(),new TeamCreate("superadmin","Local Superadmin",users.findByUsername("superadmin").orElseThrow().id(),List.of(TeamRole.CLINICIAN),List.of(new TeamSiteGrant(site,false)),"Invalid provider link"))).isInstanceOf(TeamException.class);
    }
    @Test void validatesSiteScopeAndEnablesClinicalAccessForPracticeStaffOnlyWhenExplicitlyGranted(){
        var before=team.member(actor,practice,admin);
        var roles=List.of(TeamRole.PRACTICE_ADMINISTRATOR,TeamRole.CLINICIAN);
        assertThatThrownBy(()->commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(before.version(),true,roles,List.of(new TeamSiteGrant(UUID.randomUUID(),false)),"Wrong site"))).isInstanceOf(TeamException.class);
        commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(before.version(),true,roles,List.of(new TeamSiteGrant(site,true)),"Clinician access"));
        assertThat(clinical.available(actor)).isEmpty();
        jdbc.sql("INSERT INTO practice_clinical_capability(practice_id,enabled) VALUES(:p,true)").param("p",practice).update();
        assertThat(clinical.available(actor)).hasSize(1);
        assertThat(clinical.authorize(actor,practice,site,false).actorId()).isEqualTo(admin);
        clinical.authorizePatient(actor,practice,site,ClinicalAuthorization.PatientOperation.REGISTER);
        assertThatThrownBy(()->clinical.authorize(actor,practice,site,true)).isInstanceOf(ClinicalAccessException.class);
        var current=team.member(actor,practice,admin);
        assertThatThrownBy(()->commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(current.version(),true,List.of(TeamRole.PRACTICE_ADMINISTRATOR),List.of(),"Remove clinical access"))).isInstanceOf(TeamException.class).hasMessageContaining("care-continuity");
    }
    @Test void grantsClinicalListProfilesAtomicallyAndBlocksEveryReduction(){
        var provider=users.findByUsername("superadmin").orElseThrow();
        var practiceVersion=jdbc.sql("SELECT version FROM practice_tenant_registry WHERE practice_id=:p").param("p",practice).query(Long.class).single();
        schemaUpgrades.upgrade(provider,practice,UUID.randomUUID(),practiceVersion,"Prepare list access tests");
        var member=team.member(actor,practice,admin);
        var siteAccess=commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(member.version(),true,
            List.of(TeamRole.PRACTICE_ADMINISTRATOR,TeamRole.CLINICIAN),List.of(new TeamSiteGrant(site,false)),"Assign synthetic doctor site"));
        var enabledVersion=jdbc.sql("SELECT version FROM practice_tenant_registry WHERE practice_id=:p").param("p",practice).query(Long.class).single();
        syntheticCapability.enable(provider,practice,UUID.randomUUID(),enabledVersion,"Enable synthetic list access");
        assertThat(clinicalLists.capabilities(admin,practice,site)).isEmpty();

        var profiles=List.of(
            new ClinicalListGrant(site,ClinicalListSource.ALLERGY,true,false,false,false),
            new ClinicalListGrant(site,ClinicalListSource.MEDICATION_USE,true,true,false,false),
            new ClinicalListGrant(site,ClinicalListSource.PROBLEM,true,true,true,true));
        var granted=commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(siteAccess.member().version(),true,
            siteAccess.member().roles(),siteAccess.member().siteGrants(),profiles,"Grant reviewed list profiles"));
        assertThat(granted.member().clinicalListGrants()).containsExactlyElementsOf(profiles);
        assertThat(clinicalLists.capabilities(admin,practice,site)).extracting(ClinicalChartAuthorization.Capability::sourceKind)
            .containsExactly(ClinicalChartAuthorization.SourceKind.ALLERGY,ClinicalChartAuthorization.SourceKind.MEDICATION_USE,ClinicalChartAuthorization.SourceKind.PROBLEM);
        var event=team.events(actor,practice,admin,0).getFirst();
        assertThat(event.afterAccess()).contains("clinicalListGrants","MEDICATION_USE","review");

        var reduced=List.of(profiles.get(0),profiles.get(1),new ClinicalListGrant(site,ClinicalListSource.PROBLEM,true,true,true,false));
        assertThatThrownBy(()->commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(granted.member().version(),true,
            granted.member().roles(),granted.member().siteGrants(),reduced,"Attempt to remove review")))
            .isInstanceOf(TeamException.class).hasMessageContaining("reduction");
        assertThat(team.member(actor,practice,admin).clinicalListGrants()).containsExactlyElementsOf(profiles);
    }
    @Test void clinicalListGrantValidationAndReadinessFailClosedWithoutPartialAccess(){
        var member=team.member(actor,practice,admin);
        var roles=List.of(TeamRole.PRACTICE_ADMINISTRATOR,TeamRole.CLINICIAN);
        var sites=List.of(new TeamSiteGrant(site,false));
        var invalid=List.of(new ClinicalListGrant(site,ClinicalListSource.ALLERGY,false,true,false,false));
        assertThatThrownBy(()->commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(member.version(),true,roles,sites,invalid,"Invalid action lattice")))
            .isInstanceOf(TeamException.class).hasMessageContaining("permission profile");
        var viewOnly=List.of(new ClinicalListGrant(site,ClinicalListSource.ALLERGY,true,false,false,false));
        assertThatThrownBy(()->commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(member.version(),true,roles,sites,viewOnly,"Practice is not ready")))
            .isInstanceOf(TeamException.class).hasMessageContaining("readiness");
        assertThat(team.member(actor,practice,admin).siteGrants()).isEmpty();
        assertThat(team.member(actor,practice,admin).clinicalListGrants()).isEmpty();
    }
    @Test void preservesLastAdministratorAndRejectsStaleEdits(){
        assertThatThrownBy(()->commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(0,false,List.of(TeamRole.PRACTICE_ADMINISTRATOR),List.of(),"Suspend sole administrator"))).isInstanceOf(TeamException.class).hasMessageContaining("at least one");
        var created=staff().member();
        var suspended=commands.update(actor,practice,created.userId(),UUID.randomUUID(),new TeamAccess(created.version(),false,created.roles(),List.of(),"Staff departure"));
        assertThat(suspended.member().enabled()).isFalse();
        assertThatThrownBy(()->commands.update(actor,practice,created.userId(),UUID.randomUUID(),new TeamAccess(created.version(),true,created.roles(),List.of(),"Stale reactivation"))).isInstanceOf(TeamException.class).hasMessageContaining("changed");
        assertThat(commands.update(actor,practice,created.userId(),UUID.randomUUID(),new TeamAccess(suspended.member().version(),true,List.of(TeamRole.NURSE),List.of(),"Reviewed return")).member().enabled()).isTrue();
    }
    @Test void concurrentIdenticalCreatesProduceOneMembershipAndOneCredentialResponse() throws Exception {
        var command=create(UUID.randomUUID()+"@synthetic.test",List.of(TeamRole.NURSE),List.of());var key=UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=executor.invokeAll(List.<Callable<TeamResult>>of(()->commands.create(actor,practice,key,command),()->commands.create(actor,practice,key,command)));
            var a=futures.get(0).get();var b=futures.get(1).get();
            assertThat(a.member().userId()).isEqualTo(b.member().userId());assertThat(a.replayed()).isNotEqualTo(b.replayed());
            assertThat(team.events(actor,practice,a.member().userId(),0)).hasSize(1);
        }
    }
    @Test void deniesCrossPracticeWritesAndReplayAfterAuthorityRevocation() {
        var created=staff().member();var key=UUID.randomUUID();
        var change=new TeamAccess(created.version(),false,created.roles(),List.of(),"Reviewed departure");
        commands.update(actor,practice,created.userId(),key,change);
        var unrelated=new AuthenticatedActor(created.username());
        assertThatThrownBy(()->team.member(unrelated,practice,admin)).isInstanceOf(TeamException.class);
        assertThatThrownBy(()->commands.update(unrelated,practice,created.userId(),key,change)).isInstanceOf(TeamException.class);
        assertThatThrownBy(()->team.member(actor,UUID.randomUUID(),created.userId())).isInstanceOf(TeamException.class);
        jdbc.sql("UPDATE practice_role_assignment SET enabled=false WHERE practice_id=:p AND user_id=:u AND practice_role='PRACTICE_ADMINISTRATOR'").param("p",practice).param("u",admin).update();
        assertThatThrownBy(()->commands.update(actor,practice,created.userId(),key,change)).isInstanceOf(TeamException.class);
    }
    @Test void concurrentAdministratorRemovalKeepsOneCurrentAdministrator() throws Exception {
        String email=UUID.randomUUID()+"@synthetic.test";
        var added=commands.create(actor,practice,UUID.randomUUID(),create(email,List.of(TeamRole.PRACTICE_ADMINISTRATOR),List.of())).member();
        users.completeRequiredPasswordChange(added.userId(),"SecondAdminPrivate123!",UUID.randomUUID(),OffsetDateTime.now());
        var second=new AuthenticatedActor(email);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> first=()->{try{commands.update(actor,practice,admin,UUID.randomUUID(),new TeamAccess(0,true,List.of(TeamRole.SECRETARY),List.of(),"End administration"));return true;}catch(TeamException e){return false;}};
            Callable<Boolean> other=()->{try{commands.update(second,practice,added.userId(),UUID.randomUUID(),new TeamAccess(added.version(),true,List.of(TeamRole.SECRETARY),List.of(),"End administration"));return true;}catch(TeamException e){return false;}};
            var results=executor.invokeAll(List.of(first,other));
            assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM practice_role_assignment WHERE practice_id=:p AND practice_role='PRACTICE_ADMINISTRATOR' AND enabled").param("p",practice).query(Long.class).single()).isEqualTo(1);
    }
    @Test void forcesRowLevelIsolationAndAppendOnlyEvidence() throws Exception {
        staff();
        String role="team_rls_"+UUID.randomUUID().toString().replace("-","");
        jdbc.sql("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS").update();
        jdbc.sql("GRANT USAGE ON SCHEMA public TO "+role).update();
        jdbc.sql("GRANT SELECT, UPDATE, DELETE ON practice_member_event,practice_member_command,practice_member_onboarding,practice_member_resolution_limit,practice_clinical_chart_grant,practice_clinical_chart_capability TO "+role).update();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("SET LOCAL ROLE "+role);
                try(var rows=statement.executeQuery("SELECT count(*) FROM practice_member_event")){rows.next();assertThat(rows.getInt(1)).isZero();}
                statement.execute("SELECT set_config('epr.practice_id','"+practice+"',true)");
                try(var rows=statement.executeQuery("SELECT count(*) FROM practice_member_event")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
                assertThatThrownBy(()->statement.executeUpdate("DELETE FROM practice_member_event")).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("append-only");
            } finally { connection.rollback(); }
        }
    }

    @Test void suspendingOneMembershipDoesNotChangeTheSamePersonsOtherPractice() {
        var member=staff().member();
        users.completeRequiredPasswordChange(member.userId(),"SharedIdentityPrivate123!",UUID.randomUUID(),OffsetDateTime.now());
        UUID otherPractice=UUID.randomUUID(),otherSite=UUID.randomUUID();
        provisioning.provision(users.findByUsername("superadmin").orElseThrow(),new TenantCreationRequest(otherPractice,"t-"+otherPractice.toString().substring(0,8),"Second Synthetic Practice",member.displayName(),member.username(),
            new InitialTenantSite(otherSite,"Second Synthetic Site","","","PH","1300000000","1300000000","1380300000"),UUID.randomUUID()));
        var otherActor=new AuthenticatedActor(member.username());
        assertThat(team.scopes(otherActor)).extracting(TeamScope::practiceId).containsExactly(otherPractice);
        assertThatThrownBy(()->team.members(actor,otherPractice,"","ALL","ALL",null,0,25)).isInstanceOf(TeamException.class);
        assertThatThrownBy(()->team.members(otherActor,practice,"","ALL","ALL",null,0,25)).isInstanceOf(TeamException.class);
        commands.update(actor,practice,member.userId(),UUID.randomUUID(),new TeamAccess(member.version(),false,member.roles(),List.of(),"End first Practice access"));
        assertThat(team.member(otherActor,otherPractice,member.userId()).enabled()).isTrue();
        assertThat(sites.sites(otherActor,otherPractice)).extracting(PracticeSiteDirectory.TeamSite::siteId).containsExactly(otherSite);
        assertThat(users.findByUsername(member.username()).orElseThrow().enabled()).isTrue();
    }

}
