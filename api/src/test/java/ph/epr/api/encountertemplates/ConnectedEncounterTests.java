package ph.epr.api.encountertemplates;

import static org.assertj.core.api.Assertions.*;
import ph.epr.api.ordersresults.application.ServiceRequestService;
import ph.epr.api.ordersresults.application.ServiceRequestService.*;
import ph.epr.api.prescribing.application.PrescriptionService;
import ph.epr.api.prescribing.application.PrescriptionService.*;
import ph.epr.api.carecoordination.application.ReferralService;
import ph.epr.api.carecoordination.application.ReferralService.*;
import ph.epr.api.clinicalchart.ClinicalLists;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.SourceKind;
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

@SpringBootTest(properties = {"epr.clinical.enabled=true", "epr.interventions.enabled=true", "epr.interventions.worker-enabled=false", "spring.session.jdbc.cleanup-cron=-", "logging.level.root=WARN"})
@ActiveProfiles("local")
@Testcontainers
class ConnectedEncounterTests {
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
    @Autowired JdbcClient control;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PlatformUserDirectory users;
    @Autowired TenantTransactions tenants;
    @Autowired ClinicalPatientRegistry patients;
    @Autowired ph.epr.api.patientregistry.PatientRegistrationRecords registry;
    @Autowired PregnancyRecords pregnancies;
    @Autowired JdbcTemplateRegistry templates;
    @Autowired JdbcEncounterService encounters;
    @Autowired JdbcEncounterSignatures signatures;
    @Autowired ActiveCareReservations reservations;
    @Autowired ClinicalSchemaRollout rollout;
    @Autowired ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist worklist;
    @Autowired ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue queue;
    @Autowired ServiceRequestService orders;
    @Autowired PrescriptionService prescribing;
    @Autowired ReferralService referrals;
    @Autowired ClinicalLists clinicalLists;
    @Autowired EncounterInterventionLinks links;
    @Autowired ph.epr.api.ordersresults.application.IncomingReportService reports;
    @Autowired ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts;
    @Autowired ph.epr.api.carecoordination.application.InterventionWorkService owned;
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
        for(var kind:List.of("PRESCRIPTION","LAB_ORDER","IMAGING_ORDER","REFERRAL","FOLLOW_UP")) {
            control.sql("INSERT INTO practice_intervention_capability(practice_id,kind,preparation_enabled,authorization_enabled) VALUES(:p,:k,true,true)").param("p",practiceId).param("k",kind).update();
            control.sql("INSERT INTO practice_intervention_grant(practice_id,user_id,site_id,kind,can_read,can_prepare,can_authorize,reason) VALUES(:p,:a,:s,:k,true,true,true,'Explicit synthetic test grant')").param("p",practiceId).param("a",clinician).param("s",siteId).param("k",kind).update();
        }
        return new UUID[]{practiceId, siteId};
    }
    <T> T run(Function<TenantTransactions.Context, T> work) { return tenants.execute(actor, practice, site, true, work); }
    void enableClinicalLists() {
        for(var kind:SourceKind.values()){
            control.sql("INSERT INTO practice_clinical_chart_capability(practice_id,source_kind,enabled) VALUES(:p,:k,true) ON CONFLICT (practice_id,source_kind) DO UPDATE SET enabled=true").param("p",practice).param("k",kind.name()).update();
            control.sql("INSERT INTO practice_clinical_chart_grant(practice_id,user_id,site_id,source_kind,can_read,can_propose,can_write,can_review,reason) VALUES(:p,:a,:s,:k,true,true,true,true,'Connected encounter test')").param("p",practice).param("a",clinician).param("s",site).param("k",kind.name()).update();
        }
    }
    ClinicalLists.ReviewManifest reconcile(UUID encounter) {
        var summary=run(c->clinicalLists.summary(c,patient));
        return run(c->clinicalLists.reconcile(c,patient,UUID.randomUUID(),new ClinicalLists.Reconcile(UUID.randomUUID(),encounter,
            Map.of(SourceKind.ALLERGY,summary.allergies().generation(),SourceKind.MEDICATION_USE,summary.medications().generation(),SourceKind.PROBLEM,summary.problems().generation()),
            List.of(),"Connected encounter reconciliation",true,OffsetDateTime.now())));
    }
    TemplateDefinition definition() {
        try (var stream = getClass().getResourceAsStream("/encounter-templates/synthetic/obgyn.prenatal.initial.json")) {
            return ClinicalJson.read(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), TemplateDefinition.class);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    JdbcTemplateRegistry.Release publish(TemplateDefinition definition) {
        var draft = run(c -> templates.saveDraft(c, UUID.randomUUID(), new JdbcTemplateRegistry.DraftCommand(UUID.randomUUID(), -1, definition, "Synthetic test"), true));
        var approved = run(c -> templates.approve(c, draft.draftId(), UUID.randomUUID(), new JdbcTemplateRegistry.Approval(draft.version(), draft.checksum(), "Synthetic tests only")));
        return run(c -> templates.publish(c, draft.draftId(), UUID.randomUUID(), new JdbcTemplateRegistry.Publication(UUID.randomUUID(), approved.version())));
    }
    PregnancyRecords.Episode episode() {
        return run(c -> pregnancies.create(c, patient, UUID.randomUUID(), new PregnancyRecords.CreateEpisode(UUID.randomUUID(), "UNCERTAIN", "Synthetic external account; dates unknown", null)));
    }
    JdbcEncounterService.Create creation(UUID release, UUID episode) { return new JdbcEncounterService.Create(UUID.randomUUID(), release, episode, "IN_PERSON", OffsetDateTime.parse("2026-09-19T01:00:00Z")); }

    @Test void twoVisitsReuseAnEpisodeAndKeepDatingSnapshotAndPinnedRelease() {
        var episode = episode();
        var evidenceId = UUID.randomUUID();
        var withEvidence = run(c -> pregnancies.addDating(c, patient, episode.episodeId(), UUID.randomUUID(), new PregnancyRecords.AddDating(evidenceId, episode.version(), LocalDate.of(2027, 1, 20), null, "UNKNOWN", "Clinician-entered estimate", "Synthetic external record", "REVIEWED")));
        var selected = run(c -> pregnancies.selectDating(c, patient, episode.episodeId(), UUID.randomUUID(), new PregnancyRecords.SelectDating(evidenceId, withEvidence.version(), "Clinician selected sourced estimate")));
        var release = publish(definition());
        var request = creation(release.releaseId(), episode.episodeId());
        var key = UUID.randomUUID();
        var first = run(c -> encounters.create(c, patient, key, request));
        assertThat(run(c -> encounters.create(c, patient, key, request)).encounterId()).isEqualTo(first.encounterId());
        var finished = run(c -> encounters.transition(c, patient, first.encounterId(), UUID.randomUUID(), new JdbcEncounterService.Transition(first.version(), "FINISHED", "Care finished; note remains owned")));
        encounters.releaseFinishedReservation(practice, finished);
        var correction = UUID.randomUUID();
        var withCorrection = run(c -> pregnancies.addDating(c, patient, episode.episodeId(), UUID.randomUUID(), new PregnancyRecords.AddDating(correction, selected.version(), LocalDate.of(2027, 1, 22), null, "UNKNOWN", "Corrected external estimate", "Synthetic corrected record", "REVIEWED")));
        run(c -> pregnancies.selectDating(c, patient, episode.episodeId(), UUID.randomUUID(), new PregnancyRecords.SelectDating(correction, withCorrection.version(), "Reviewed corrected source")));
        run(c -> templates.availability(c, release.releaseId(), UUID.randomUUID(), new JdbcTemplateRegistry.Availability(0, true, "Superseded for new visits")));
        assertThat(run(c -> encounters.get(c, patient, first.encounterId(), true)).episodeSnapshot().selectedDating().assessmentId()).isEqualTo(evidenceId);
        assertThatThrownBy(() -> run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(release.releaseId(), episode.episodeId())))).hasMessage("TEMPLATE_RETIRED");
        var old = definition();
        var v2 = new TemplateDefinition(old.schemaVersion(), old.ruleVersion(), old.rendererVersion(), old.extractionVersion(), old.terminologyVersion(), old.templateKey(), old.title(), old.specialty(), old.encounterType(), "2.0.0", true, true, old.sections());
        var newer = publish(v2);
        var second = run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(newer.releaseId(), episode.episodeId())));
        assertThat(second.episodeSnapshot().episode().episodeId()).isEqualTo(episode.episodeId());
        assertThat(second.episodeSnapshot().selectedDating().assessmentId()).isEqualTo(correction);
        assertThat(second.answers()).isEmpty();
        assertThat(run(c -> encounters.get(c, patient, first.encounterId(), false)).release().releaseId()).isEqualTo(release.releaseId());
        assertThat(finished.noteState()).isEqualTo("DRAFT");
        assertThat(finished.unsignedNoteOwner()).isEqualTo(clinician);
        run(c->{encounters.refreshPatientVisits(c);return null;});
        var directory=run(c->registry.directory(c,"Synthetic Patient","all","all","last-visit",0,20,false,"visited"));
        assertThat(directory.visitHistoryState()).isEqualTo("CURRENT");
        assertThat(directory.items()).hasSize(1);
        assertThat(directory.items().getFirst().lastVisit()).isEqualTo(second.clinicalTime());
    }
    @Test void draftSaveIsVersionedAndExactReplayDoesNotDuplicateRevisions() {
        var release = publish(definition()); var episode = episode();
        var created = run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(release.releaseId(), episode.episodeId())));
        var field = definition().sections().getFirst().fields().getFirst();
        var answer = new Answer(field.id(), null, new Answer.Value("Synthetic visit account", null, null, List.of(), null, null, List.of()), null,
            new Answer.Source("PATIENT_REPORTED", null, null, null, "Synthetic patient account"), Answer.Review.REVIEWED, null, null);
        var request = new JdbcEncounterService.Save(0, OffsetDateTime.now(), List.of(new JdbcEncounterService.Change(field.id(), null, answer)));
        var key = UUID.randomUUID();
        var saved = run(c -> encounters.save(c, patient, created.encounterId(), key, request));
        assertThat(saved.responseRevision()).isEqualTo(1);
        assertThat(run(c -> encounters.save(c, patient, created.encounterId(), key, request)).version()).isEqualTo(saved.version());
        assertThatThrownBy(() -> run(c -> encounters.save(c, patient, created.encounterId(), UUID.randomUUID(), request))).hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(() -> run(c -> encounters.save(c, patient, created.encounterId(), key, new JdbcEncounterService.Save(1, request.clientTime(), List.of())))).hasMessage("IDEMPOTENCY_PAYLOAD_CONFLICT");
        assertThat(run(c -> encounters.get(c, patient, created.encounterId(), false)).answers()).containsExactly(answer);
        assertThatThrownBy(() -> run(c -> { c.jdbc().sql("DELETE FROM encounter_response_revision").update(); return null; })).hasMessageContaining("DELETE FROM encounter_response_revision");
    }
    @Test void approvalEditInvalidatesApprovalAndTenantPatientSiteAndRevocationFailClosed() {
        var draft = run(c -> templates.saveDraft(c, UUID.randomUUID(), new JdbcTemplateRegistry.DraftCommand(UUID.randomUUID(), -1, definition(), "Synthetic draft"), true));
        var approved = run(c -> templates.approve(c, draft.draftId(), UUID.randomUUID(), new JdbcTemplateRegistry.Approval(0, draft.checksum(), "Synthetic review")));
        var edited = run(c -> templates.saveDraft(c, UUID.randomUUID(), new JdbcTemplateRegistry.DraftCommand(draft.draftId(), approved.version(), definition(), "Revised draft"), false));
        assertThat(edited.approvedBy()).isNull();
        assertThatThrownBy(() -> run(c -> templates.publish(c, draft.draftId(), UUID.randomUUID(), new JdbcTemplateRegistry.Publication(UUID.randomUUID(), edited.version())))).hasMessage("TEMPLATE_APPROVAL_REQUIRED");
        assertThatThrownBy(() -> tenants.execute(actor, practice, UUID.randomUUID(), false, patients::list)).hasMessage("CLINICAL_ACCESS_DENIED");
        assertThatThrownBy(() -> run(c -> patients.requirePatient(c, UUID.randomUUID()))).hasMessage("PATIENT_UNAVAILABLE");
        var other = provision(actor.username());
        assertThatThrownBy(() -> tenants.execute(actor, other[0], other[1], false, c -> patients.requirePatient(c, patient))).hasMessage("PATIENT_UNAVAILABLE");
        assertThatThrownBy(() -> tenants.execute(new AuthenticatedActor("superadmin"), practice, site, false, patients::list)).hasMessage("CLINICAL_ACCESS_DENIED");
        control.sql("UPDATE practice_membership SET enabled = false WHERE practice_id = :practice AND user_id = :actor")
            .param("practice", practice).param("actor", clinician).update();
        assertThatThrownBy(() -> run(patients::list)).hasMessage("CLINICAL_ACCESS_DENIED");
    }
    @Test void concurrentStartsAndCrossPracticeAttemptsCannotCreateDoubleCare() throws Exception {
        var release = publish(definition()); var episode = episode();
        var request = creation(release.releaseId(), episode.episodeId()); var key = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<JdbcEncounterService.Encounter>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> { gate.await(); return run(c -> encounters.create(c, patient, key, request)); }));
            gate.countDown();
            assertThat(tasks.get(0).get(20, TimeUnit.SECONDS).encounterId()).isEqualTo(tasks.get(1).get(20, TimeUnit.SECONDS).encounterId());
        }
        assertThatThrownBy(() -> run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(release.releaseId(), episode.episodeId())))).hasMessage("ACTIVE_CARE_REQUIRES_RECONCILIATION");
        var other = provision(actor.username());
        var oldPractice = practice; var oldSite = site; var oldPatient = patient;
        practice = other[0]; site = other[1]; patient = UUID.randomUUID();
        run(c -> patients.register(c, UUID.randomUUID(), new ClinicalPatientRegistry.Registration(patient, "Other Synthetic Patient", null, "PROVISIONAL", true, false)));
        var otherRelease = publish(definition()); var otherEpisode = episode();
        assertThatThrownBy(() -> run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(otherRelease.releaseId(), otherEpisode.episodeId())))).hasMessage("ACTIVE_CARE_REQUIRES_RECONCILIATION");
        practice = oldPractice; site = oldSite; patient = oldPatient;
    }
    @Test void failedTenantCommitKeepsFenceUntilCancellationAndDelayedRetryCannotStart() {
        var release = publish(definition()); var episode = episode();
        var request = creation(release.releaseId(), episode.episodeId()); var key = UUID.randomUUID();
        assertThatThrownBy(() -> run(c -> { encounters.create(c, patient, key, request); throw new IllegalStateException("Injected before commit"); })).hasMessage("Injected before commit");
        assertThat(reservations.fence(clinician, practice, request.encounterId())).isPresent();
        var cancelled = run(c -> encounters.cancelUncommittedStart(c, patient, UUID.randomUUID(), new JdbcEncounterService.CancelStart(request.encounterId(), "Confirmed failed start")));
        encounters.releaseCancelledReservation(clinician, practice, cancelled);
        assertThat(reservations.fence(clinician, practice, request.encounterId())).isEmpty();
        assertThatThrownBy(() -> run(c -> encounters.create(c, patient, key, request))).hasMessage("ENCOUNTER_START_CANCELLED");
        var created = run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(release.releaseId(), episode.episodeId())));
        assertThatThrownBy(() -> run(c -> encounters.cancelUncommittedStart(c, patient, UUID.randomUUID(), new JdbcEncounterService.CancelStart(created.encounterId(), "Must not cancel care")))).hasMessage("ENCOUNTER_COMMITTED_RESUME_REQUIRED");
    }
    JdbcEncounterService.Encounter completeDraft() {
        var release = publish(definition()); var episode = episode();
        var created = run(c -> encounters.create(c, patient, UUID.randomUUID(), creation(release.releaseId(), episode.episodeId())));
        var changes = new TemplateCompiler().validate(definition()).values().stream().filter(TemplateDefinition.Field::requiredAtSign).map(field -> {
            var answer = new Answer(field.id(), null, null, field.allowedMissingStates().iterator().next(),
                new Answer.Source("CLINICIAN_ENTERED", null, null, null, "Reviewed synthetic missing state"), Answer.Review.REVIEWED, null, null);
            return new JdbcEncounterService.Change(field.id(), null, answer);
        }).toList();
        return run(c -> encounters.save(c, patient, created.encounterId(), UUID.randomUUID(), new JdbcEncounterService.Save(created.version(), OffsetDateTime.now(), changes)));
    }
    @Test void signatureBindsExactBytesSurvivesAmendmentAndRejectsChangedArtifact() throws Exception {
        var draft = completeDraft();
        var prepareKey = UUID.randomUUID(); var prepare = new JdbcEncounterSignatures.Prepare(UUID.randomUUID(), draft.version());
        var ready = run(c -> signatures.prepare(c, patient, draft.encounterId(), prepareKey, prepare));
        assertThat((Object) run(c -> signatures.prepare(c, patient, draft.encounterId(), prepareKey, prepare))).isEqualTo(ready);
        var bytes = run(c -> signatures.candidateBytes(c, patient, draft.encounterId(), ready.preparationId()));
        var signKey = UUID.randomUUID(); var sign = new JdbcEncounterSignatures.Sign(ready.preparationId(), ready.encounterVersion(), ready.artifactHash(), true);
        var signed = run(c -> signatures.sign(c, patient, draft.encounterId(), signKey, sign));
        assertThat((Object) run(c -> signatures.sign(c, patient, draft.encounterId(), signKey, sign))).isEqualTo(signed);
        var current = run(c -> encounters.get(c, patient, draft.encounterId(), false));
        assertThat(current.careState()).isEqualTo("IN_PROGRESS");
        assertThat(current.noteState()).isEqualTo("SIGNED");
        assertThatThrownBy(() -> run(c -> encounters.save(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterService.Save(current.version(), OffsetDateTime.now(), List.of())))).hasMessage("SIGNED_NOTE_IMMUTABLE");
        var amendment = run(c -> signatures.amend(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Amendment(current.version(), signed.signatureId(), "Reviewed correction")));
        assertThat(amendment.noteState()).isEqualTo("DRAFT");
        assertThat((byte[]) run(c -> signatures.signedBytes(c, patient, draft.encounterId(), signed.signatureId()))).isEqualTo(bytes);
        assertThatThrownBy(() -> run(c -> { c.jdbc().sql("DELETE FROM encounter_signature").update(); return null; })).hasMessageContaining("DELETE FROM encounter_signature");
        var path = ARTIFACTS.resolve(practice.toString()).resolve(signed.artifactId() + ".html");
        java.nio.file.Files.writeString(path, "Injected changed bytes");
        assertThatThrownBy(() -> run(c -> signatures.signedBytes(c, patient, draft.encounterId(), signed.signatureId()))).hasMessage("ARTIFACT_INTEGRITY_FAILURE");
        java.nio.file.Files.delete(path);
        assertThatThrownBy(() -> run(c -> signatures.signedBytes(c, patient, draft.encounterId(), signed.signatureId()))).hasMessage("ARTIFACT_UNAVAILABLE");
    }
    @Test void editedCandidateCannotSignAndMissingStorageNeverMarksSigned() throws Exception {
        var draft = completeDraft();
        var ready = run(c -> signatures.prepare(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Prepare(UUID.randomUUID(), draft.version())));
        var edited = run(c -> encounters.save(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterService.Save(ready.encounterVersion(), OffsetDateTime.now(), List.of())));
        assertThatThrownBy(() -> run(c -> signatures.sign(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Sign(ready.preparationId(), ready.encounterVersion(), ready.artifactHash(), true)))).hasMessage("VERSION_CONFLICT");
        var newer = run(c -> signatures.prepare(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Prepare(UUID.randomUUID(), edited.version())));
        java.nio.file.Files.delete(ARTIFACTS.resolve(practice.toString()).resolve(newer.artifactId() + ".html"));
        assertThatThrownBy(() -> run(c -> signatures.sign(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Sign(newer.preparationId(), newer.encounterVersion(), newer.artifactHash(), true)))).hasMessage("ARTIFACT_UNAVAILABLE");
        assertThat(run(c -> encounters.get(c, patient, draft.encounterId(), false)).noteState()).isEqualTo("READY_TO_SIGN");
        assertThat((java.util.List<?>) run(c -> signatures.signatures(c, patient, draft.encounterId()))).isEmpty();
    }
    @Test void queueStartIsAtomicExplicitAndCompletionIsSeparateFromSigning() {
        var release = publish(definition()); var episode = episode();
        var session = run(c -> queue.create(c, UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.SessionCreate(UUID.randomUUID(), LocalDate.now(), "Synthetic session")));
        var opened = run(c -> queue.transition(c, session.sessionId(), UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.SessionTransition(session.version(), "OPEN", "Open", session.selectionVersion())));
        assertThat(opened.selected()).isFalse();
        var active = run(c -> queue.transition(c, session.sessionId(), UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.SessionTransition(opened.version(), "IN_PROGRESS", "Explicit activation", opened.selectionVersion())));
        var entry = run(c -> queue.add(c, session.sessionId(), UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.QueueAdd(UUID.randomUUID(), patient, active.version())));
        var create = new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.QueueStart(active.version(), entry.version(), UUID.randomUUID(), release.releaseId(), episode.episodeId(), "IN_PERSON", OffsetDateTime.now());
        assertThatThrownBy(() -> run(c -> queue.start(c, session.sessionId(), entry.queueEntryId(), UUID.randomUUID(), create))).hasMessage("PATIENT_NOT_READY");
        var ready = run(c -> queue.transition(c, session.sessionId(), entry.queueEntryId(), UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.QueueTransition(entry.version(), "READY_FOR_DOCTOR", "Clinician reviewed readiness")));
        var start = new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.QueueStart(active.version(), ready.version(), UUID.randomUUID(), release.releaseId(), episode.episodeId(), "IN_PERSON", OffsetDateTime.now());
        var key = UUID.randomUUID();
        var started = run(c -> queue.start(c, session.sessionId(), entry.queueEntryId(), key, start));
        assertThat(run(c -> queue.start(c, session.sessionId(), entry.queueEntryId(), key, start)).encounterId()).isEqualTo(started.encounterId());
        assertThatThrownBy(() -> run(c -> queue.transition(c, session.sessionId(), entry.queueEntryId(), UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.QueueTransition(started.version(), "COMPLETED", "Complete")))).hasMessage("CARE_FINISH_REQUIRED");
        var encounter = run(c -> encounters.get(c, patient, started.encounterId(), false));
        var finished = run(c -> encounters.transition(c, patient, encounter.encounterId(), UUID.randomUUID(), new JdbcEncounterService.Transition(encounter.version(), "FINISHED", "Care finished; draft owned")));
        encounters.releaseFinishedReservation(practice, finished);
        assertThat(run(c -> queue.transition(c, session.sessionId(), entry.queueEntryId(), UUID.randomUUID(), new ph.epr.api.schedulingqueue.infrastructure.JdbcClinicalQueue.QueueTransition(started.version(), "COMPLETED", "Queue visit complete"))).state()).isEqualTo("COMPLETED");
        assertThat(finished.noteState()).isEqualTo("DRAFT");
    }

    @Test void projectionReplayIsDeduplicatedAndSigningDoesNotCloseFollowUp() {
        var draft = completeDraft(); var intent = UUID.randomUUID();
        var receipt = run(c -> encounters.followUp(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterService.FollowUp(intent, draft.version(), LocalDate.now().plusDays(7), "Clinician-recorded synthetic follow-up")));
        assertThat(receipt.deliveryState()).isEqualTo("PENDING");
        assertThat((List<?>) run(worklist::list)).isEmpty();
        var tasks = run(worklist::reconcile);
        assertThat(tasks).hasSize(2);
        assertThat((List<?>) run(worklist::reconcile)).hasSize(2);
        var ready = run(c -> signatures.prepare(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Prepare(UUID.randomUUID(), draft.version())));
        run(c -> signatures.sign(c, patient, draft.encounterId(), UUID.randomUUID(), new JdbcEncounterSignatures.Sign(ready.preparationId(), ready.encounterVersion(), ready.artifactHash(), true)));
        var after = run(worklist::reconcile);
        assertThat(after.stream().filter(t -> t.kind().equals("FOLLOW_UP")).findFirst().orElseThrow().state()).isEqualTo("OPEN");
        assertThat(after.stream().filter(t -> t.kind().equals("UNSIGNED_NOTE")).findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
        var follow = after.stream().filter(t -> t.kind().equals("FOLLOW_UP")).findFirst().orElseThrow();
        run(c -> worklist.resolve(c, follow.sourceId(), UUID.randomUUID(), new ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist.Resolve(follow.version(), "Reviewed disposition")));
        assertThat(run(worklist::reconcile).stream().filter(t -> t.kind().equals("FOLLOW_UP")).findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
    }

    @Test void migrationQuarantineFailsClosedAndHistoryCannotBeRewritten() {
        var operation = UUID.randomUUID();
        var version = control.sql("SELECT version FROM practice_tenant_registry WHERE practice_id = :practice").param("practice", practice).query(Long.class).single();
        var claim = rollout.begin(practice, operation, version, clinician, "Synthetic canary", "15");
        assertThatThrownBy(() -> run(patients::list)).hasMessage("CLINICAL_ACCESS_DENIED");
        rollout.finish(practice, operation, claim.attempt(), clinician, "15", false);
        assertThatThrownBy(() -> run(patients::list)).hasMessage("CLINICAL_ACCESS_DENIED");
        var retry = rollout.begin(practice, operation, version, clinician, "Synthetic canary", "15");
        rollout.finish(practice, operation, retry.attempt(), clinician, "15", true);
        assertThat(run(patients::list).getFirst().patientId()).isEqualTo(patient);
        assertThatThrownBy(() -> rollout.begin(practice, operation, version, clinician, "Changed reason", "15")).hasMessage("IDEMPOTENCY_PAYLOAD_CONFLICT");
        assertThatThrownBy(() -> control.sql("DELETE FROM clinical_schema_rollout_event WHERE practice_id = :practice").param("practice", practice).update()).hasMessageContaining("DELETE FROM clinical_schema_rollout_event");
    }
    @Test void twoPracticesRacingReserveOnlyOneActiveEncounter() throws Exception {
        var firstPractice = practice; var firstSite = site; var firstPatient = patient;
        var firstRelease = publish(definition()); var firstEpisode = episode();
        var setup = provision(actor.username()); practice = setup[0]; site = setup[1]; patient = UUID.randomUUID();
        run(c -> patients.register(c, UUID.randomUUID(), new ClinicalPatientRegistry.Registration(patient, "Second Practice Synthetic Patient", null, "PROVISIONAL", true, false)));
        var secondRelease = publish(definition()); var secondEpisode = episode();
        var firstCommand = creation(firstRelease.releaseId(), firstEpisode.episodeId()); var secondCommand = creation(secondRelease.releaseId(), secondEpisode.episodeId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); try { tenants.execute(actor, firstPractice, firstSite, false, c -> encounters.create(c, firstPatient, UUID.randomUUID(), firstCommand)); return true; } catch (ClinicalAccessException e) { assertThat(e.getMessage()).isEqualTo("ACTIVE_CARE_REQUIRES_RECONCILIATION"); return false; } });
            var second = executor.submit(() -> { gate.await(); try { run(c -> encounters.create(c, patient, UUID.randomUUID(), secondCommand)); return true; } catch (ClinicalAccessException e) { assertThat(e.getMessage()).isEqualTo("ACTIVE_CARE_REQUIRES_RECONCILIATION"); return false; } });
            gate.countDown(); assertThat(first.get(20, TimeUnit.SECONDS)).isNotEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        var firstCount = tenants.execute(actor, firstPractice, firstSite, false, c -> encounters.list(c, firstPatient).size());
        var secondCount = run(c -> encounters.list(c, patient).size());
        assertThat(firstCount + secondCount).isEqualTo(1);
    }


    ServiceRequestData lab(String instruction) { return new ServiceRequestData("LAB_ORDER",null,"SYN-LAB-A","synthetic-1","Synthetic laboratory service A","Synthetic clinical question","ROUTINE","Synthetic external facility","","","",instruction,LocalDate.now().plusDays(3)); }
    ServiceRequestRecord labDraft(UUID encounter) {return run(c->orders.create(c,patient,UUID.randomUUID(),new ServiceRequestCreate(UUID.randomUUID(),encounter,OffsetDateTime.now(),lab("Synthetic instructions"),null)));}
    ServiceRequestRecord authorizeLab(ServiceRequestRecord draft) {
        var candidate=run(c->orders.prepare(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestPrepare(UUID.randomUUID(),draft.version())));
        return run(c->orders.authorize(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestAuthorize(draft.version(),candidate.preparationId(),candidate.artifactHash(),true,null,"Reviewed repeat synthetic intent")));
    }
    @Test void freeTextLabRequestCanBeAuthorizedAndRepeatedIntentNeedsAReason() {
        var encounter=completeDraft();var time=OffsetDateTime.now();
        var test=new ServiceRequestData("LAB_ORDER",null,null,null,"Synthetic unlisted test 71","Assess the reported concern","STAT","Synthetic external lab","","","","Fasting required.",LocalDate.now().plusDays(1));
        var draft=run(c->orders.create(c,patient,UUID.randomUUID(),new ServiceRequestCreate(UUID.randomUUID(),encounter.encounterId(),time,test,null)));
        var authorized=authorizeLab(draft);
        assertThat(authorized.data().catalogCode()).isNull();
        assertThat(new String(run(c->orders.artifact(c,patient,draft.id(),null)),java.nio.charset.StandardCharsets.UTF_8)).contains("Synthetic unlisted test 71","Fasting required.");
        var repeated=new ServiceRequestData("LAB_ORDER",null,null,null,"  synthetic UNLISTED test 71  ","Assess the reported concern","ROUTINE","Synthetic external lab","","","","",LocalDate.now().plusDays(2));
        var second=run(c->orders.create(c,patient,UUID.randomUUID(),new ServiceRequestCreate(UUID.randomUUID(),encounter.encounterId(),time,repeated,null)));
        var candidate=run(c->orders.prepare(c,patient,second.id(),UUID.randomUUID(),new ServiceRequestPrepare(UUID.randomUUID(),second.version())));
        assertThatThrownBy(()->run(c->orders.authorize(c,patient,second.id(),UUID.randomUUID(),new ServiceRequestAuthorize(second.version(),candidate.preparationId(),candidate.artifactHash(),true,null,null)))).hasMessage("DUPLICATE_INTENT_REASON_REQUIRED");
        var incomplete=new ServiceRequestData("LAB_ORDER",null,"SYN-LAB-A",null,"Synthetic laboratory service A","Question","ROUTINE","Synthetic external lab","","","","",LocalDate.now());
        assertThatThrownBy(()->run(c->orders.create(c,patient,UUID.randomUUID(),new ServiceRequestCreate(UUID.randomUUID(),encounter.encounterId(),time,incomplete,null)))).hasMessage("CATALOG_REFERENCE_INCOMPLETE");
    }
    @Test void interventionDraftsPersistAndReplayReauthorizesWithoutChangingNoteRevision() {
        var encounter=completeDraft();var key=UUID.randomUUID();var command=new ServiceRequestCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),lab("Persist me"),null);
        var first=run(c->orders.create(c,patient,key,command));
        assertThat(run(c->orders.create(c,patient,key,command)).id()).isEqualTo(first.id());
        assertThat(run(c->orders.get(c,patient,first.id())).data().instructions()).isEqualTo("Persist me");
        assertThat(run(c->encounters.get(c,patient,encounter.encounterId(),false)).responseRevision()).isEqualTo(encounter.responseRevision());
        assertThat(run(c->links.list(c,patient,encounter.encounterId(),0,30)).items()).hasSize(1);
        assertThatThrownBy(()->run(c->orders.create(c,patient,key,new ServiceRequestCreate(command.id(),command.encounterId(),command.occurredAt(),lab("Changed"),null)))).hasMessage("IDEMPOTENCY_PAYLOAD_CONFLICT");
        control.sql("UPDATE practice_intervention_grant SET can_prepare=false WHERE practice_id=:p AND user_id=:a AND kind='LAB_ORDER'").param("p",practice).param("a",clinician).update();
        assertThatThrownBy(()->run(c->orders.create(c,patient,key,command))).hasMessage("INTERVENTION_ACCESS_DENIED");
    }
    @Test void orderAuthorizationOwnsLaterCorrectedResultAndPreservesAuthorizedBytes() {
        var encounter=completeDraft();var draft=labDraft(encounter.encounterId());var authorized=authorizeLab(draft);
        var original=run(c->orders.artifact(c,patient,draft.id(),null));
        assertThat(authorized.state()).isEqualTo("AUTHORIZED");assertThat(authorized.fulfillmentState()).isEqualTo("NOT_STARTED");
        assertThat(run(owned::list)).hasSize(1);
        assertThatThrownBy(()->run(c->orders.update(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestUpdate(authorized.version(),lab("changed"))))).hasMessage("AUTHORIZED_RECORD_IMMUTABLE");
        var received=run(c->orders.result(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestEvidence(authorized.version(),"RECEIVED","Matched synthetic report reference R1",OffsetDateTime.now())));
        var reviewed=run(c->orders.review(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestEvidence(received.version(),"REVIEWED","Reviewed report and recorded disposition",OffsetDateTime.now())));
        assertThat(run(owned::list).getFirst().state()).isEqualTo("RESOLVED");
        var corrected=run(c->orders.result(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestEvidence(reviewed.version(),"CORRECTED","Matched correction to report R1; original retained",OffsetDateTime.now())));
        assertThat(corrected.reviewState()).isEqualTo("CORRECTED");assertThat(run(owned::list).getFirst().state()).isEqualTo("OPEN");
        assertThat((byte[])run(c->orders.artifact(c,patient,draft.id(),null))).isEqualTo(original);
        assertThatThrownBy(()->run(c->{c.jdbc().sql("DELETE FROM service_request_revision WHERE practice_id=:p AND id=:id").param("p",c.practiceId()).param("id",draft.id()).update();return null;})).hasMessageContaining("DELETE FROM service_request_revision");
    }
    @Test void materialInterventionChangeInvalidatesNoteCandidateButNeverChangesSignedHistory() {
        var encounter=completeDraft();var draft=labDraft(encounter.encounterId());
        var candidate=run(c->signatures.prepare(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Prepare(UUID.randomUUID(),encounter.version())));
        var authorized=authorizeLab(draft);
        assertThatThrownBy(()->run(c->signatures.sign(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Sign(candidate.preparationId(),candidate.encounterVersion(),candidate.artifactHash(),true)))).hasMessage("SIGN_CANDIDATE_STALE");
        var fresh=run(c->signatures.prepare(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Prepare(UUID.randomUUID(),candidate.encounterVersion())));
        var signed=run(c->signatures.sign(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Sign(fresh.preparationId(),fresh.encounterVersion(),fresh.artifactHash(),true)));
        var original=run(c->signatures.signedBytes(c,patient,encounter.encounterId(),signed.signatureId()));
        run(c->orders.cancel(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestReason(authorized.version(),"Synthetic cancellation; contact obligation remains owned")));
        assertThat((byte[])run(c->signatures.signedBytes(c,patient,encounter.encounterId(),signed.signatureId()))).isEqualTo(original);
        assertThat(run(c->links.manifest(c,patient,encounter.encounterId(),fresh.interventionManifestId())).items().getFirst().summary().clinicalState()).isEqualTo("AUTHORIZED");
        assertThat(run(c->links.list(c,patient,encounter.encounterId(),0,30)).items().getFirst().summary().clinicalState()).isEqualTo("CANCELLED");
    }
    @Test void prescriptionRequiresReviewedSafetyAndKeepsStrengthSeparateFromDose() {
        var encounter=completeDraft();
        enableClinicalLists();
        var item=new PrescriptionItem(UUID.randomUUID(),"Synthetic ingredient A","","100 synthetic units","Synthetic tablet",new java.math.BigDecimal("2"),"synthetic dose units","Synthetic route","Synthetic frequency","Synthetic duration",new java.math.BigDecimal("4"),"synthetic tablets",0,"Synthetic instructions","SYN-MED-A","synthetic-1",null,null);
        var draft=run(c->prescribing.create(c,patient,UUID.randomUUID(),new PrescriptionCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),new PrescriptionData("Synthetic indication","",null,List.of(item)),null)));
        assertThatThrownBy(()->run(c->prescribing.prepare(c,patient,draft.id(),UUID.randomUUID(),new PrescriptionPrepare(UUID.randomUUID(),0)))).hasMessage("MEDICATION_SAFETY_REVIEW_REQUIRED");
        var allergy=run(c->clinicalLists.createAllergy(c,patient,UUID.randomUUID(),new ClinicalLists.CreateAllergy(UUID.randomUUID(),new ClinicalLists.AllergyData("Synthetic ingredient A",new ClinicalLists.Code("urn:epr:synthetic:medication","SYN-MED-A","synthetic-1","Synthetic ingredient A"),"MEDICATION","ALLERGY","CONFIRMED","HIGH",null,"UNKNOWN",null,"Synthetic patient report",null,null,OffsetDateTime.now(),encounter.encounterId(),List.of(new ClinicalLists.Reaction(UUID.randomUUID(),"Rash","MODERATE",null,null))))));
        var knownSummary=run(c->clinicalLists.summary(c,patient));
        var knownCommand=new ClinicalLists.Reconcile(UUID.randomUUID(),encounter.encounterId(),Map.of(SourceKind.ALLERGY,knownSummary.allergies().generation(),SourceKind.MEDICATION_USE,knownSummary.medications().generation(),SourceKind.PROBLEM,knownSummary.problems().generation()),List.of(),"Explicit synthetic reconciliation",true,OffsetDateTime.now());
        var known=run(c->clinicalLists.reconcile(c,patient,UUID.randomUUID(),knownCommand));
        var withAllergy=run(c->prescribing.update(c,patient,draft.id(),UUID.randomUUID(),new PrescriptionUpdate(0,new PrescriptionData("Synthetic indication","",known.reviewId(),List.of(item)))));
        assertThatThrownBy(()->run(c->prescribing.prepare(c,patient,draft.id(),UUID.randomUUID(),new PrescriptionPrepare(UUID.randomUUID(),withAllergy.version())))).hasMessage("KNOWN_ALLERGY_MATCH");
        run(c->clinicalLists.transitionAllergy(c,patient,allergy.id(),UUID.randomUUID(),new ClinicalLists.Transition(allergy.version(),"RESOLVE","Reviewed historical reaction")));
        var clearSummary=run(c->clinicalLists.summary(c,patient));
        var clearCommand=new ClinicalLists.Reconcile(UUID.randomUUID(),encounter.encounterId(),Map.of(SourceKind.ALLERGY,clearSummary.allergies().generation(),SourceKind.MEDICATION_USE,clearSummary.medications().generation(),SourceKind.PROBLEM,clearSummary.problems().generation()),List.of(),"Explicit separate reviewed synthetic fixture",true,OffsetDateTime.now());
        var review=run(c->clinicalLists.reconcile(c,patient,UUID.randomUUID(),clearCommand));
        var ready=run(c->prescribing.update(c,patient,draft.id(),UUID.randomUUID(),new PrescriptionUpdate(withAllergy.version(),new PrescriptionData("Synthetic indication","",review.reviewId(),List.of(item)))));
        var preparation=run(c->prescribing.prepare(c,patient,draft.id(),UUID.randomUUID(),new PrescriptionPrepare(UUID.randomUUID(),ready.version())));
        var issued=run(c->prescribing.authorize(c,patient,draft.id(),UUID.randomUUID(),new PrescriptionAuthorize(ready.version(),preparation.preparationId(),preparation.artifactHash(),true,null,null)));
        assertThat(issued.data().items().getFirst().strength()).isEqualTo("100 synthetic units");assertThat(issued.data().items().getFirst().dose()).isEqualByComparingTo("2");
        assertThat(issued.state()).isEqualTo("ISSUED");assertThat(issued.deliveryState()).isEqualTo("NOT_SENT");
        var catalog=run(prescribing::catalog);
        assertThat(catalog).anySatisfy(entry->{assertThat(entry.code()).isEqualTo("SYN-MED-A");assertThat(entry.strength()).isEqualTo("100 synthetic units");assertThat(entry.formulation()).isEqualTo("Synthetic tablet");assertThat(entry.doseUnit()).isEqualTo("synthetic dose units");});
        var refill=new PrescriptionItem(UUID.randomUUID(),item.ingredient(),item.brand(),item.strength(),item.formulation(),item.dose(),item.doseUnit(),item.route(),item.frequency(),item.duration(),item.quantity(),item.quantityUnit(),0,item.instructions(),item.catalogCode(),item.catalogVersion(),issued.id(),item.itemId());
        var freeText=new PrescriptionItem(UUID.randomUUID(),"Synthetic custom medicine","","25 synthetic units","Synthetic capsule",new java.math.BigDecimal("1"),"synthetic dose units","Synthetic route","Synthetic frequency","Synthetic duration",new java.math.BigDecimal("3"),"synthetic capsules",0,"Synthetic instructions",null,null,null,null);
        var combined=run(c->prescribing.create(c,patient,UUID.randomUUID(),new PrescriptionCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),new PrescriptionData("Synthetic combined indication","",review.reviewId(),List.of(refill,freeText)),null)));
        assertThat(combined.data().items()).hasSize(2);
        assertThat(combined.data().items()).anySatisfy(saved->{assertThat(saved.sourcePrescriptionId()).isEqualTo(issued.id());assertThat(saved.sourceItemId()).isEqualTo(item.itemId());});
        var combinedPrep=run(c->prescribing.prepare(c,patient,combined.id(),UUID.randomUUID(),new PrescriptionPrepare(UUID.randomUUID(),combined.version())));
        var combinedIssued=run(c->prescribing.authorize(c,patient,combined.id(),UUID.randomUUID(),new PrescriptionAuthorize(combined.version(),combinedPrep.preparationId(),combinedPrep.artifactHash(),true,null,"Intentional synthetic refill of the issued item")));
        assertThat(combinedIssued.state()).isEqualTo("ISSUED");
        assertThat(run(c->prescribing.get(c,patient,issued.id())).state()).isEqualTo("ISSUED");
        var changedRefill=new PrescriptionItem(UUID.randomUUID(),"Different medicine","",refill.strength(),refill.formulation(),refill.dose(),refill.doseUnit(),refill.route(),refill.frequency(),refill.duration(),refill.quantity(),refill.quantityUnit(),0,refill.instructions(),refill.catalogCode(),refill.catalogVersion(),issued.id(),item.itemId());
        assertThatThrownBy(()->run(c->prescribing.create(c,patient,UUID.randomUUID(),new PrescriptionCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),new PrescriptionData("Invalid refill","",review.reviewId(),List.of(changedRefill)),null)))).hasMessage("REFILL_MEDICINE_CHANGED");
    }
    @Test void clinicalListChangeInvalidatesPreparedNoteAndSignedBytesKeepExactReview() {
        var encounter=completeDraft();enableClinicalLists();var review=reconcile(encounter.encounterId());
        var candidate=run(c->signatures.prepare(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Prepare(UUID.randomUUID(),encounter.version())));
        assertThat(candidate.clinicalReviewId()).isEqualTo(review.reviewId());
        run(c->clinicalLists.createProblem(c,patient,UUID.randomUUID(),new ClinicalLists.CreateProblem(UUID.randomUUID(),0,new ClinicalLists.ProblemData("Synthetic problem",new ClinicalLists.Code("urn:epr:synthetic:problem","synthetic","1","Synthetic problem"),"CONFIRMED",null,"UNKNOWN",null,"UNKNOWN",null,"Clinician entered",null,OffsetDateTime.now(),encounter.encounterId()))));
        assertThatThrownBy(()->run(c->signatures.sign(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Sign(candidate.preparationId(),candidate.encounterVersion(),candidate.artifactHash(),true)))).hasMessage("CLINICAL_LIST_REVIEW_STALE");
        var currentReview=reconcile(encounter.encounterId());
        var fresh=run(c->signatures.prepare(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Prepare(UUID.randomUUID(),candidate.encounterVersion())));
        assertThat(fresh.clinicalReviewId()).isEqualTo(currentReview.reviewId());
        var signed=run(c->signatures.sign(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Sign(fresh.preparationId(),fresh.encounterVersion(),fresh.artifactHash(),true)));
        var bytes=(byte[])run(c->signatures.signedBytes(c,patient,encounter.encounterId(),signed.signatureId()));
        assertThat(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).contains(currentReview.reviewId().toString(),currentReview.manifestHash());
    }
    @Test void summaryAndArtifactCannotBypassRevokedSourceReadPermission() {
        var encounter=completeDraft();var authorized=authorizeLab(labDraft(encounter.encounterId()));
        control.sql("UPDATE practice_intervention_grant SET can_read=false WHERE practice_id=:p AND user_id=:a AND kind='LAB_ORDER'").param("p",practice).param("a",clinician).update();
        assertThat(run(c->links.list(c,patient,encounter.encounterId(),0,30)).total()).isZero();
        assertThatThrownBy(()->run(c->orders.get(c,patient,authorized.id()))).hasMessage("INTERVENTION_ACCESS_DENIED");
        assertThatThrownBy(()->run(c->orders.artifact(c,patient,authorized.id(),null))).hasMessage("INTERVENTION_ACCESS_DENIED");
        assertThatThrownBy(()->run(c->signatures.prepare(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Prepare(UUID.randomUUID(),encounter.version())))).hasMessage("INTERVENTION_ACCESS_DENIED");
    }
    @Test void concurrentInterventionEditsHaveOneWinnerAndOneVersionConflict() throws Exception {
        var encounter=completeDraft();var draft=labDraft(encounter.encounterId());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(var text:List.of("First","Second"))futures.add(executor.submit(()->{gate.await();try{run(c->orders.update(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestUpdate(0,lab(text))));return true;}catch(ClinicalAccessException e){assertThat(e.getMessage()).isEqualTo("VERSION_CONFLICT");return false;}}));
            gate.countDown();assertThat(List.of(futures.get(0).get(),futures.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat((List<?>)run(c->orders.history(c,patient,draft.id()))).hasSize(2);
    }


    @Test void groupedRequestsRemainIndividualAndArchivedGroupBytesNeverChange() {
        var encounter=completeDraft();var group=UUID.randomUUID();var key=UUID.randomUUID();
        var a=new ServiceRequestData("LAB_ORDER",group,"SYN-LAB-A","synthetic-1","Synthetic laboratory service A","Question A","ROUTINE","Synthetic facility","","","","",LocalDate.now());
        var b=new ServiceRequestData("LAB_ORDER",group,"SYN-LAB-B","synthetic-1","Synthetic laboratory service B","Question B","ROUTINE","Synthetic facility","","","","",LocalDate.now());
        var command=new ServiceRequestBatch(group,List.of(new ServiceRequestCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),a,null),new ServiceRequestCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),b,null)));
        var batch=run(c->orders.createBatch(c,patient,key,command));
        assertThat((ServiceRequestBatchResult)run(c->orders.createBatch(c,patient,key,command))).isEqualTo(batch);
        assertThatThrownBy(()->run(c->orders.captureGroup(c,patient,group,UUID.randomUUID(),new GroupPrint(UUID.randomUUID())))).hasMessage("GROUP_HAS_UNAUTHORIZED_REQUESTS");
        var first=authorizeLab(batch.requests().get(0));var second=authorizeLab(batch.requests().get(1));
        var capture=run(c->orders.captureGroup(c,patient,group,UUID.randomUUID(),new GroupPrint(UUID.randomUUID())));
        var staged=artifacts.stageDetached(practice,capture.documentId(),capture.bytes());
        var document=run(c->orders.finalizeGroup(c,patient,UUID.randomUUID(),capture,staged));
        run(c->orders.cancel(c,patient,first.id(),UUID.randomUUID(),new ServiceRequestReason(first.version(),"Only this service cancelled")));
        assertThat(run(c->orders.get(c,patient,second.id())).state()).isEqualTo("AUTHORIZED");
        assertThat((byte[])run(c->orders.groupArtifact(c,patient,group,document.documentId()))).isEqualTo(capture.bytes());
        assertThatThrownBy(()->run(c->orders.finalizeGroup(c,patient,UUID.randomUUID(),capture,staged))).hasMessage("GROUP_DOCUMENT_STALE");
    }
    @Test void signingRacingAnInterventionEditHasASerialOutcome() throws Exception {
        var encounter=completeDraft();var draft=labDraft(encounter.encounterId());
        var candidate=run(c->signatures.prepare(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Prepare(UUID.randomUUID(),encounter.version())));
        var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var signing=executor.submit(()->{gate.await();try {run(c->signatures.sign(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterSignatures.Sign(candidate.preparationId(),candidate.encounterVersion(),candidate.artifactHash(),true)));return "SIGNED";}catch(ClinicalAccessException e){return e.getMessage();}});
            var editing=executor.submit(()->{gate.await();try {run(c->orders.update(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestUpdate(draft.version(),lab("Concurrent clinical update"))));return "UPDATED";}catch(ClinicalAccessException e){return e.getMessage();}});
            gate.countDown();var signed=signing.get(20,TimeUnit.SECONDS);var edited=editing.get(20,TimeUnit.SECONDS);
            if(signed.equals("SIGNED"))assertThat(edited).isNotEqualTo("UPDATED");else {assertThat(signed).isEqualTo("SIGN_CANDIDATE_STALE");assertThat(edited).isEqualTo("UPDATED");}
        }
    }

    @Test void orphanCleanupRetainsAttachedHistoryAndRetriesCanRecreateDetachedBytes() throws Exception {
        var encounter=completeDraft();var authorized=authorizeLab(labDraft(encounter.encounterId()));
        var orphan=UUID.randomUUID();var bytes="Synthetic unattached candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        artifacts.stageDetached(practice,orphan,bytes);
        var old=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        java.nio.file.Files.setLastModifiedTime(ARTIFACTS.resolve(practice.toString()).resolve(orphan+".html"),old);
        java.nio.file.Files.setLastModifiedTime(ARTIFACTS.resolve(practice.toString()).resolve(authorized.artifactId()+".html"),old);
        assertThat((int)run(artifacts::cleanupUnattached)).isEqualTo(1);
        assertThat((byte[])run(c->orders.artifact(c,patient,authorized.id(),null))).isNotEmpty();
        var recreated=artifacts.stageDetached(practice,orphan,bytes);
        assertThat(recreated.sha256()).isEqualTo(ph.epr.api.documentscommunication.PrivateClinicalArtifacts.sha256(bytes));
    }

    @Test void disablingNewCommandsPreservesReadAndDispositionOfExistingWork() {
        var encounter=completeDraft();var order=authorizeLab(labDraft(encounter.encounterId()));
        control.sql("UPDATE practice_intervention_capability SET preparation_enabled=false,authorization_enabled=false WHERE practice_id=:p").param("p",practice).update();
        assertThatThrownBy(()->labDraft(encounter.encounterId())).hasMessage("INTERVENTION_ACCESS_DENIED");
        assertThat(run(c->links.list(c,patient,encounter.encounterId(),0,30)).items()).hasSize(1);
        var received=run(c->orders.result(c,patient,order.id(),UUID.randomUUID(),new ServiceRequestEvidence(order.version(),"RECEIVED","Later report remains owned after rollback",OffsetDateTime.now())));
        var reviewed=run(c->orders.review(c,patient,order.id(),UUID.randomUUID(),new ServiceRequestEvidence(received.version(),"REVIEWED","Reviewed after rollback",OffsetDateTime.now())));
        assertThat(reviewed.reviewState()).isEqualTo("REVIEWED");
        assertThat((byte[])run(c->orders.artifact(c,patient,order.id(),null))).isNotEmpty();
    }

    @Test void deliveryWorkerPersistsBackoffThenRecoversTheSameObligation() {
        var encounter=completeDraft();var intent=UUID.randomUUID();
        run(c->encounters.followUp(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterService.FollowUp(intent,encounter.version(),LocalDate.now().plusDays(1),"Synthetic recoverable follow-up")));
        var delegate=org.mockito.Mockito.mock(ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist.class);
        org.mockito.Mockito.when(delegate.reconcile(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("Synthetic delivery failure")).thenAnswer(invocation->worklist.reconcile(invocation.getArgument(0)));
        var worker=new ph.epr.api.carecoordination.infrastructure.ClinicalDeliveryWorker(()->List.of(new ClinicalDeliveryScopes.DeliveryScope(actor.username(),practice,site)),tenants,delegate,artifacts);
        worker.poll();
        assertThat((int)run(c->c.jdbc().sql("SELECT attempts FROM clinical_delivery_checkpoint WHERE practice_id=:p AND actor_id=:a").param("p",practice).param("a",clinician).query(Integer.class).single())).isEqualTo(1);
        run(c->{c.jdbc().sql("UPDATE clinical_delivery_checkpoint SET next_attempt_at=CURRENT_TIMESTAMP WHERE practice_id=:p").param("p",practice).update();return null;});
        worker.poll();worker.poll();
        assertThat(run(worklist::list).stream().filter(t->t.sourceId().equals(intent)).count()).isEqualTo(1);
        assertThat((int)run(c->c.jdbc().sql("SELECT attempts FROM clinical_delivery_checkpoint WHERE practice_id=:p AND actor_id=:a").param("p",practice).param("a",clinician).query(Integer.class).single())).isZero();
    }

    AuthenticatedActor peer(UUID id) {
        var username=id+"@synthetic.test";
        control.sql("INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,created_at,updated_at) VALUES(:id,:username,'Synthetic receiving clinician','unused','CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("id",id).param("username",username).update();
        control.sql("INSERT INTO practice_membership(practice_id,user_id,enabled,created_at,updated_at) VALUES(:p,:id,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("p",practice).param("id",id).update();
        control.sql("INSERT INTO practice_role_assignment(practice_id,user_id,practice_role,enabled,created_at,updated_at) VALUES(:p,:id,'CLINICIAN',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("p",practice).param("id",id).update();
        control.sql("INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,reason,granted_by) VALUES(:p,:id,:s,true,true,'Explicit test handoff',:a)").param("p",practice).param("id",id).param("s",site).param("a",clinician).update();
        for(var kind:List.of("LAB_ORDER","IMAGING_ORDER","REFERRAL","FOLLOW_UP"))control.sql("INSERT INTO practice_intervention_grant(practice_id,user_id,site_id,kind,can_read,can_prepare,can_authorize,reason) VALUES(:p,:id,:s,:k,true,false,true,'Explicit test handoff')").param("p",practice).param("id",id).param("s",site).param("k",kind).update();
        return new AuthenticatedActor(username);
    }
    @Test void acceptedHandoffSurvivesReconciliationWithoutGrantingChartAccess() {
        var encounter=completeDraft();var authorized=authorizeLab(labDraft(encounter.encounterId()));
        var peerId=UUID.randomUUID();var receiver=peer(peerId);
        var work=run(owned::list).getFirst();
        var proposed=run(c->owned.transfer(c,work.kind(),work.sourceId(),UUID.randomUUID(),new ph.epr.api.carecoordination.application.InterventionWorkService.TransferWork(work.version(),peerId,"Explicit handoff requested")));
        assertThat(proposed.ownerId()).isEqualTo(clinician);
        assertThatThrownBy(()->tenants.execute(receiver,practice,site,false,c->orders.get(c,patient,authorized.id()))).hasMessage("ACCEPTED_WORK_ASSIGNMENT_REQUIRED");
        var accepted=tenants.execute(receiver,practice,site,false,c->owned.accept(c,work.kind(),work.sourceId(),UUID.randomUUID(),new ph.epr.api.carecoordination.application.InterventionWorkService.AcceptWork(proposed.version(),"Responsibility accepted")));
        assertThat(accepted.ownerId()).isEqualTo(peerId);
        var received=tenants.execute(receiver,practice,site,false,c->orders.result(c,patient,authorized.id(),UUID.randomUUID(),new ServiceRequestEvidence(authorized.version(),"RECEIVED","Synthetic matched report for accepted work",OffsetDateTime.now())));
        assertThat(received.reviewState()).isEqualTo("RECEIVED");
        assertThat(tenants.execute(receiver,practice,site,false,owned::list).getFirst().ownerId()).isEqualTo(peerId);
        assertThatThrownBy(()->tenants.execute(receiver,practice,site,false,c->encounters.get(c,patient,encounter.encounterId(),false))).hasMessage("PATIENT_UNAVAILABLE");
        var intent=run(c->encounters.followUp(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterService.FollowUp(UUID.randomUUID(),encounter.version(),LocalDate.now().plusDays(1),"Synthetic follow-up handoff")));
        var task=run(worklist::reconcile).stream().filter(t->t.sourceId().equals(intent.intentId())).findFirst().orElseThrow();
        var handoff=run(c->worklist.transfer(c,task.sourceId(),UUID.randomUUID(),new ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist.TransferFollowUp(task.version(),peerId,"Accept continuing follow-up")));
        var acceptedTask=tenants.execute(receiver,practice,site,false,c->worklist.accept(c,task.sourceId(),UUID.randomUUID(),new ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist.AcceptFollowUp(handoff.version(),"Accepted")));
        run(worklist::reconcile);
        assertThat(tenants.execute(receiver,practice,site,false,worklist::list).stream().filter(t->t.sourceId().equals(task.sourceId())).findFirst().orElseThrow().ownerId()).isEqualTo(peerId);
        tenants.execute(receiver,practice,site,false,c->worklist.resolve(c,task.sourceId(),UUID.randomUUID(),new ph.epr.api.carecoordination.infrastructure.JdbcClinicalWorklist.Resolve(acceptedTask.version(),"Contact completed and disposition recorded")));
        run(worklist::reconcile);
        assertThat(tenants.execute(receiver,practice,site,false,worklist::list).stream().filter(t->t.sourceId().equals(task.sourceId())).findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
    }
    @Test void unmatchedReportsRequireExplicitMatchingAndCorrectionsPreserveOriginals() {
        var encounter=completeDraft();var order=authorizeLab(labDraft(encounter.encounterId()));
        var command=new ph.epr.api.ordersresults.application.IncomingReportService.ReceiveReport(UUID.randomUUID(),"LAB_ORDER","Synthetic laboratory","R-001","Synthetic original result",OffsetDateTime.now(),null);
        var report=run(c->reports.receive(c,UUID.randomUUID(),command));
        assertThat(report.state()).isEqualTo("UNMATCHED");assertThat(report.patientId()).isNull();
        assertThatThrownBy(()->run(c->reports.match(c,report.id(),UUID.randomUUID(),new ph.epr.api.ordersresults.application.IncomingReportService.MatchReport(0,patient,order.id(),order.version(),"Not confirmed",false)))).hasMessage("EXPLICIT_PATIENT_SOURCE_MATCH_REQUIRED");
        var matched=run(c->reports.match(c,report.id(),UUID.randomUUID(),new ph.epr.api.ordersresults.application.IncomingReportService.MatchReport(0,patient,order.id(),order.version(),"Reviewed source, identifier and request",true)));
        assertThat(matched.state()).isEqualTo("MATCHED");
        var current=run(c->orders.get(c,patient,order.id()));
        var reviewed=run(c->orders.review(c,patient,order.id(),UUID.randomUUID(),new ServiceRequestEvidence(current.version(),"REVIEWED","Synthetic disposition",OffsetDateTime.now())));
        var correction=run(c->reports.receive(c,UUID.randomUUID(),new ph.epr.api.ordersresults.application.IncomingReportService.ReceiveReport(UUID.randomUUID(),"LAB_ORDER","Synthetic laboratory","R-001-correction","Synthetic corrected result",OffsetDateTime.now(),report.id())));
        run(c->reports.match(c,correction.id(),UUID.randomUUID(),new ph.epr.api.ordersresults.application.IncomingReportService.MatchReport(0,patient,order.id(),reviewed.version(),"Reviewed explicit correction",true)));
        assertThat(run(c->reports.get(c,report.id())).content()).isEqualTo("Synthetic original result");
        assertThat(run(c->orders.get(c,patient,order.id())).reviewState()).isEqualTo("CORRECTED");assertThat(run(owned::list).getFirst().state()).isEqualTo("OPEN");
    }
    @Test void referralReleaseKeepsOwnershipAndDocumentAfterRefusalAndCancellation() {
        var encounter=completeDraft();var data=new ReferralData("Synthetic receiving service","Synthetic referral question","Clinician-selected synthetic summary","ROUTINE",LocalDate.now().plusDays(3),"Only this selected summary is authorized for disclosure");
        var draft=run(c->referrals.create(c,patient,UUID.randomUUID(),new ReferralCreate(UUID.randomUUID(),encounter.encounterId(),OffsetDateTime.now(),data,null)));
        var candidate=run(c->referrals.prepare(c,patient,draft.id(),UUID.randomUUID(),new ReferralPrepare(UUID.randomUUID(),0)));
        var released=run(c->referrals.authorize(c,patient,draft.id(),UUID.randomUUID(),new ReferralAuthorize(0,candidate.preparationId(),candidate.artifactHash(),true,null,null)));
        assertThat(released.deliveryState()).isEqualTo("NOT_SENT");var bytes=run(c->referrals.artifact(c,patient,draft.id(),null));
        var refused=run(c->referrals.outcome(c,patient,draft.id(),UUID.randomUUID(),new ReferralEvidence(released.version(),"PATIENT_REFUSED","Patient declined synthetic referral; alternatives remain owned",OffsetDateTime.now())));
        var cancelled=run(c->referrals.cancel(c,patient,draft.id(),UUID.randomUUID(),new ReferralReason(refused.version(),"Arrange an alternative and confirm cancellation notice")));
        assertThat(cancelled.state()).isEqualTo("CANCELLED");assertThat(run(owned::list).getFirst().state()).isEqualTo("OPEN");
        assertThat((byte[])run(c->referrals.artifact(c,patient,draft.id(),null))).isEqualTo(bytes);
    }
    @Test void detachedPreparationRejectsAConcurrentChangeAndWorkerDeliversWithoutInboxRead() {
        var encounter=completeDraft();var draft=labDraft(encounter.encounterId());
        var request=new ServiceRequestPrepare(UUID.randomUUID(),draft.version());
        var content=run(c->orders.candidateContent(c,patient,draft.id(),request));var staged=artifacts.stageDetached(practice,request.preparationId(),content);
        run(c->orders.update(c,patient,draft.id(),UUID.randomUUID(),new ServiceRequestUpdate(draft.version(),lab("Changed after capture"))));
        assertThatThrownBy(()->run(c->orders.prepare(c,patient,draft.id(),UUID.randomUUID(),request,staged))).hasMessage("VERSION_CONFLICT");
        var follow=run(c->encounters.followUp(c,patient,encounter.encounterId(),UUID.randomUUID(),new JdbcEncounterService.FollowUp(UUID.randomUUID(),encounter.version(),LocalDate.now().plusDays(1),"Synthetic worker delivery")));
        var worker=new ph.epr.api.carecoordination.infrastructure.ClinicalDeliveryWorker(()->List.of(new ClinicalDeliveryScopes.DeliveryScope(actor.username(),practice,site)),tenants,worklist,artifacts);
        worker.poll();worker.poll();
        assertThat(run(worklist::list).stream().filter(t->t.sourceId().equals(follow.intentId())).count()).isEqualTo(1);
        assertThat(run(c->c.jdbc().sql("SELECT attempts FROM clinical_delivery_checkpoint WHERE practice_id=:p AND actor_id=:a").param("p",c.practiceId()).param("a",c.actorId()).query(Integer.class).single()).intValue()).isZero();
    }
    @Test void backupRestoresInterventionHistoryAndArtifactsWithoutChangingSourcePractice() throws Exception {
        var encounter=completeDraft();var order=authorizeLab(labDraft(encounter.encounterId()));
        var original=run(c->orders.artifact(c,patient,order.id(),null));
        var database=run(c->c.jdbc().sql("SELECT current_database()").query(String.class).single());
        var backup="/tmp/interventions-"+UUID.randomUUID()+".dump";var restored="restore_"+UUID.randomUUID().toString().replace("-","");
        assertThat(POSTGRES.execInContainer("pg_dump","-U",POSTGRES.getUsername(),"-Fc","-f",backup,database).getExitCode()).isZero();
        assertThat(POSTGRES.execInContainer("createdb","-U",POSTGRES.getUsername(),restored).getExitCode()).isZero();
        assertThat(POSTGRES.execInContainer("pg_restore","-U",POSTGRES.getUsername(),"--exit-on-error","--no-owner","--no-acl","-d",restored,backup).getExitCode()).isZero();
        var count=POSTGRES.execInContainer("psql","-U",POSTGRES.getUsername(),"-d",restored,"-Atc","SELECT count(*) FROM service_request_revision");
        assertThat(count.getStdout().trim()).isEqualTo("2");
        var restoredHash=POSTGRES.execInContainer("psql","-U",POSTGRES.getUsername(),"-d",restored,"-Atc","SELECT artifact_hash FROM service_request WHERE state='AUTHORIZED'");
        assertThat(restoredHash.getStdout().trim()).isEqualTo(ph.epr.api.documentscommunication.PrivateClinicalArtifacts.sha256(original));
        var archiveCopy=java.nio.file.Files.createTempFile(ARTIFACTS,"restored-",".html");java.nio.file.Files.write(archiveCopy,original);
        assertThat(ph.epr.api.documentscommunication.PrivateClinicalArtifacts.sha256(java.nio.file.Files.readAllBytes(archiveCopy))).isEqualTo(order.artifactHash());
        assertThat(run(c->orders.get(c,patient,order.id())).state()).isEqualTo("AUTHORIZED");
        assertThat((byte[])run(c->orders.artifact(c,patient,order.id(),null))).isEqualTo(original);
    }

}
