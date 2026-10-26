package ph.epr.api.encountertemplates.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ph.epr.api.documentscommunication.PrivateClinicalArtifacts;
import ph.epr.api.encountertemplates.EncounterSigningDocumentIssuer;
import ph.epr.api.encountertemplates.EncounterInterventionLinks;
import ph.epr.api.encountertemplates.domain.*;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.identitytenancy.ClinicalDocumentIdentity;
import ph.epr.api.identitytenancy.PracticeProvisioningRegistry;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
@Profile("local")
public class JdbcEncounterSignatures {
    private final JdbcEncounterService encounters;
    private final PrivateClinicalArtifacts artifacts;
    private final TenantCommandJournal journal;
    private final ph.epr.api.encountertemplates.EncounterInterventionLinks links;
    private final ph.epr.api.clinicalchart.ClinicalLists clinicalLists;
    private final ph.epr.api.clinicalchart.EncounterVitals vitals;
    private final PracticeProvisioningRegistry practices;
    private final ClinicalDocumentIdentity identities;
    private final List<EncounterSigningDocumentIssuer> issuers;
    public JdbcEncounterSignatures(JdbcEncounterService encounters, PrivateClinicalArtifacts artifacts, TenantCommandJournal journal, ph.epr.api.encountertemplates.EncounterInterventionLinks links,ph.epr.api.clinicalchart.ClinicalLists clinicalLists, ph.epr.api.clinicalchart.EncounterVitals vitals, PracticeProvisioningRegistry practices, ClinicalDocumentIdentity identities, List<EncounterSigningDocumentIssuer> issuers) {
        this.encounters = encounters; this.artifacts = artifacts; this.journal = journal; this.links=links;this.clinicalLists=clinicalLists;
        this.vitals = vitals; this.practices = practices; this.identities = identities; this.issuers = List.copyOf(issuers);
    }
    public record Prepare(UUID preparationId, long expectedVersion) {}
    public record Preparation(UUID preparationId, long responseRevision, long encounterVersion, UUID artifactId, String artifactHash, String releaseChecksum, Long interventionSetVersion, UUID interventionManifestId,UUID clinicalReviewId,String clinicalReviewHash) {}
    public record Sign(UUID preparationId, long expectedVersion, String reviewedArtifactHash, boolean confirmSyntheticReview, String duplicateIntentReason) {
        public Sign(UUID preparationId, long expectedVersion, String reviewedArtifactHash, boolean confirmSyntheticReview) {
            this(preparationId, expectedVersion, reviewedArtifactHash, confirmSyntheticReview, null);
        }
    }
    public record Signature(UUID signatureId, UUID preparationId, long responseRevision, UUID artifactId, String artifactHash,
                            UUID signerId, OffsetDateTime signedAt, String assuranceMethod, UUID amendmentOf, String amendmentReason, boolean interventionSnapshotCaptured) {}
    public record Amendment(long expectedVersion, UUID originalSignatureId, String reason) {}
    public record NoteCandidate(UUID preparationId,long encounterVersion,byte[] bytes) {}
    public NoteCandidate captureCandidate(Context c,UUID patient,UUID encounter,UUID key,Prepare request) {
        encounters.get(c,patient,encounter,false);
        var candidate=journal.execute(c,"capture-note-candidate/"+encounter,key,request,NoteCandidate.class,()->{
            encounters.lockEncounter(c,patient,encounter);var current=encounters.get(c,patient,encounter,false);
            version(current.version(),request.expectedVersion());
            if("SIGNED".equals(current.noteState())||current.responseRevision()==0)throw new ClinicalAccessException("SERVER_SAVED_DRAFT_REQUIRED",422);
            var errors=new AnswerValidator().validate(current.release().definition(),current.answers(),AnswerValidator.Stage.SIGN);
            if(!errors.isEmpty())throw new ClinicalAccessException("SIGN_REVIEW_REQUIRED:"+String.join(",",errors),422);
            var manifest=links.capture(c,patient,encounter,request.preparationId());
            checkDocuments(c, patient, encounter, manifest);
            var clinicalReview=clinicalReview(c,patient,encounter);
            return new NoteCandidate(request.preparationId(),current.version(),render(c,current,manifest,clinicalReview));
        });
        links.manifest(c,patient,encounter,candidate.preparationId());return candidate;
    }
    public Preparation prepare(Context c, UUID patient, UUID encounter, UUID key, Prepare request) {
        return prepare(c,patient,encounter,key,request,null);
    }
    public Preparation prepare(Context c, UUID patient, UUID encounter, UUID key, Prepare request,PrivateClinicalArtifacts.Artifact staged) {
        encounters.get(c, patient, encounter, false);
        if (request.preparationId() == null) throw new ClinicalAccessException("PREPARATION_ID_REQUIRED", 422);
        return journal.execute(c, "prepare-signature/" + encounter, key, request, Preparation.class, () -> {
            encounters.lockEncounter(c, patient, encounter);
            var current = encounters.get(c, patient, encounter, false);
            version(current.version(), request.expectedVersion());
            if ("SIGNED".equals(current.noteState())) throw new ClinicalAccessException("SIGNED_NOTE_IMMUTABLE", 409);
            if (current.responseRevision() == 0) throw new ClinicalAccessException("SERVER_SAVED_REVISION_REQUIRED", 422);
            var errors = new AnswerValidator().validate(current.release().definition(), current.answers(), AnswerValidator.Stage.SIGN);
            if (!errors.isEmpty()) throw new ClinicalAccessException("SIGN_REVIEW_REQUIRED:" + String.join(",", errors), 422);
            var manifest=staged==null?links.capture(c,patient,encounter,request.preparationId()):links.manifest(c,patient,encounter,request.preparationId());
            if(manifest.interventionSetVersion()!=links.version(c,patient,encounter))throw new ClinicalAccessException("SIGN_CANDIDATE_STALE",409);
            checkDocuments(c, patient, encounter, manifest);
            var clinicalReview=clinicalReview(c,patient,encounter);
            var artifact = staged==null?artifacts.stage(c, request.preparationId(), render(c, current, manifest,clinicalReview)):staged;
            if(staged!=null){if(!PrivateClinicalArtifacts.sha256(render(c,current,manifest,clinicalReview)).equals(staged.sha256()))throw new ClinicalAccessException("SIGN_CANDIDATE_STALE",409);artifacts.attach(c,staged);}
            var result = new Preparation(request.preparationId(), current.responseRevision(), current.version() + 1, artifact.artifactId(), artifact.sha256(), current.release().checksum(), manifest.interventionSetVersion(), manifest.manifestId(),clinicalReview==null?null:clinicalReview.reviewId(),clinicalReview==null?null:clinicalReview.manifestHash());
            c.jdbc().sql("""
                INSERT INTO encounter_sign_preparation(practice_id, patient_id, encounter_id, preparation_id, response_revision,
                    encounter_version, release_checksum, artifact_id, artifact_hash, created_by, intervention_set_version, intervention_manifest_id,clinical_review_id,clinical_review_hash)
                VALUES (:practice, :patient, :encounter, :id, :revision, :version, :checksum, :artifact, :hash, :actor, :setVersion, :manifest,:clinicalReview,:clinicalReviewHash)
                """).param("practice", c.practiceId()).param("patient", patient).param("encounter", encounter).param("id", result.preparationId())
                .param("revision", result.responseRevision()).param("version", result.encounterVersion()).param("checksum", result.releaseChecksum())
                .param("artifact", result.artifactId()).param("hash", result.artifactHash()).param("actor", c.actorId()).param("setVersion",result.interventionSetVersion()).param("manifest",result.interventionManifestId())
                .param("clinicalReview",result.clinicalReviewId(),Types.OTHER).param("clinicalReviewHash",result.clinicalReviewHash(),Types.CHAR).update();
            c.jdbc().sql("UPDATE clinical_encounter SET note_state = 'READY_TO_SIGN', version = version + 1 WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id")
                .param("practice", c.practiceId()).param("patient", patient).param("id", encounter).update();
            journal.audit(c, encounter, "SIGN_PREPARATION_READY", null);
            return result;
        });
    }
    public Signature sign(Context c, UUID patient, UUID encounter, UUID key, Sign request) {
        encounters.get(c, patient, encounter, false);
        var reviewedPreparation=preparation(c,patient,encounter,request.preparationId());
        if(reviewedPreparation.interventionManifestId()!=null)links.manifest(c,patient,encounter,reviewedPreparation.interventionManifestId());
        if (!request.confirmSyntheticReview()) throw new ClinicalAccessException("DELIBERATE_REVIEW_REQUIRED", 422);
        return journal.execute(c, "sign-encounter/" + encounter, key, request, Signature.class, () -> {
            encounters.lockEncounter(c, patient, encounter);
            var current = encounters.get(c, patient, encounter, false);
            version(current.version(), request.expectedVersion());
            var preparation = preparation(c, patient, encounter, request.preparationId());
            var clinicalReview=clinicalReview(c,patient,encounter);
            if (!"READY_TO_SIGN".equals(current.noteState()) || preparation.encounterVersion() != current.version()
                || preparation.responseRevision() != current.responseRevision() || !preparation.releaseChecksum().equals(current.release().checksum())
                || !preparation.artifactHash().equals(request.reviewedArtifactHash())
                || preparation.interventionSetVersion()==null || preparation.interventionSetVersion()!=links.version(c,patient,encounter)
                || !Objects.equals(preparation.clinicalReviewId(),clinicalReview==null?null:clinicalReview.reviewId())
                || !Objects.equals(preparation.clinicalReviewHash(),clinicalReview==null?null:clinicalReview.manifestHash())) throw new ClinicalAccessException("SIGN_CANDIDATE_STALE", 409);
            var manifest = links.manifest(c,patient,encounter,preparation.interventionManifestId());
            if (!preparation.artifactHash().equals(PrivateClinicalArtifacts.sha256(render(c,current,manifest,clinicalReview))))
                throw new ClinicalAccessException("SIGN_CANDIDATE_STALE", 409);
            for (var item : manifest.items()) if ("DRAFT".equals(item.summary().clinicalState())
                && item.summary().kind() != EncounterInterventionLinks.Kind.FOLLOW_UP)
                issuer(item.summary().kind()).issueWithSignature(c, patient, encounter, item, preparation.preparationId(), request.duplicateIntentReason());
            artifacts.archive(c, preparation.artifactId(), preparation.artifactHash());
            var amendment = c.jdbc().sql("SELECT amendment_of_signature_id, amendment_reason FROM clinical_encounter WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id")
                .param("practice", c.practiceId()).param("patient", patient).param("id", encounter)
                .query((rs, n) -> new AmendmentLink(rs.getObject(1, UUID.class), rs.getString(2))).single();
            var id = UUID.randomUUID();
            c.jdbc().sql("""
                INSERT INTO encounter_signature(practice_id, patient_id, encounter_id, signature_id, preparation_id, response_revision,
                    artifact_id, artifact_hash, signer_id, assurance_method, amendment_of, amendment_reason)
                VALUES (:practice, :patient, :encounter, :id, :preparation, :revision, :artifact, :hash, :actor, 'LOCAL_SYNTHETIC_CONFIRMATION', :original, :reason)
                """).param("practice", c.practiceId()).param("patient", patient).param("encounter", encounter).param("id", id)
                .param("preparation", preparation.preparationId()).param("revision", preparation.responseRevision()).param("artifact", preparation.artifactId())
                .param("hash", preparation.artifactHash()).param("actor", c.actorId()).param("original", amendment.id(), Types.OTHER)
                .param("reason", amendment.reason(), Types.VARCHAR).update();
            c.jdbc().sql("UPDATE clinical_encounter SET note_state = 'SIGNED', version = version + 1 WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id")
                .param("practice", c.practiceId()).param("patient", patient).param("id", encounter).update();
            journal.audit(c, encounter, "SYNTHETIC_NOTE_SIGNED", amendment.reason());
            journal.event(c, encounter, "NOTE_SIGNED/" + current.version(), c.actorId());
            return signatures(c, patient, encounter).stream().filter(s -> s.signatureId().equals(id)).findFirst().orElseThrow();
        });
    }
    public List<Signature> signatures(Context c, UUID patient, UUID encounter) {
        encounters.get(c, patient, encounter, false);
        return c.jdbc().sql("SELECT * FROM encounter_signature WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id ORDER BY signed_at")
            .param("practice", c.practiceId()).param("patient", patient).param("id", encounter).query((rs, n) -> new Signature(rs.getObject("signature_id", UUID.class),
                rs.getObject("preparation_id", UUID.class), rs.getLong("response_revision"), rs.getObject("artifact_id", UUID.class), rs.getString("artifact_hash"),
                rs.getObject("signer_id", UUID.class), rs.getObject("signed_at", OffsetDateTime.class), rs.getString("assurance_method"),
                rs.getObject("amendment_of", UUID.class), rs.getString("amendment_reason"), preparation(c,patient,encounter,rs.getObject("preparation_id",UUID.class)).interventionManifestId()!=null)).list();
    }
    public byte[] candidateBytes(Context c, UUID patient, UUID encounter, UUID preparationId) {
        var current = encounters.get(c, patient, encounter, false);
        var preparation = preparation(c, patient, encounter, preparationId);
        var clinicalReview=clinicalReview(c,patient,encounter);
        if (preparation.encounterVersion() != current.version() || !"READY_TO_SIGN".equals(current.noteState()) || preparation.interventionSetVersion()==null || preparation.interventionSetVersion()!=links.version(c,patient,encounter)
            || !Objects.equals(preparation.clinicalReviewId(),clinicalReview==null?null:clinicalReview.reviewId())||!Objects.equals(preparation.clinicalReviewHash(),clinicalReview==null?null:clinicalReview.manifestHash())) throw new ClinicalAccessException("SIGN_CANDIDATE_STALE", 409);
        links.manifest(c,patient,encounter,preparation.interventionManifestId());
        journal.audit(c, encounter, "SIGN_CANDIDATE_READ", null);
        return artifacts.readVerified(c, preparation.artifactId(), preparation.artifactHash());
    }
    public byte[] signedBytes(Context c, UUID patient, UUID encounter, UUID signatureId) {
        var signature = signatures(c, patient, encounter).stream().filter(s -> s.signatureId().equals(signatureId)).findFirst()
            .orElseThrow(() -> new ClinicalAccessException("SIGNATURE_UNAVAILABLE", 404));
        var historical=preparation(c,patient,encounter,signature.preparationId());
        if(historical.interventionManifestId()!=null) links.manifest(c,patient,encounter,historical.interventionManifestId());
        journal.audit(c, encounter, "SIGNED_ARTIFACT_READ", null);
        return artifacts.readVerified(c, signature.artifactId(), signature.artifactHash());
    }
    public JdbcEncounterService.Encounter amend(Context c, UUID patient, UUID encounter, UUID key, Amendment request) {
        encounters.get(c, patient, encounter, false);
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) throw new ClinicalAccessException("AMENDMENT_REASON_REQUIRED", 422);
        return journal.execute(c, "amend-encounter/" + encounter, key, request, JdbcEncounterService.Encounter.class, () -> {
            encounters.lockEncounter(c, patient, encounter);
            var current = encounters.get(c, patient, encounter, false); version(current.version(), request.expectedVersion());
            if (!"SIGNED".equals(current.noteState())) throw new ClinicalAccessException("SIGNED_ORIGINAL_REQUIRED", 409);
            if (signatures(c, patient, encounter).stream().noneMatch(s -> s.signatureId().equals(request.originalSignatureId()))) throw new ClinicalAccessException("SIGNATURE_UNAVAILABLE", 404);
            c.jdbc().sql("""
                UPDATE clinical_encounter SET note_state = 'DRAFT', version = version + 1, amendment_of_signature_id = :original, amendment_reason = :reason
                WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :id
                """).param("original", request.originalSignatureId()).param("reason", request.reason()).param("practice", c.practiceId()).param("patient", patient).param("id", encounter).update();
            journal.audit(c, encounter, "AMENDMENT_DRAFT_CREATED", request.reason());
            journal.event(c, encounter, "UNSIGNED_AMENDMENT_CREATED/" + current.version(), c.actorId());
            return encounters.get(c, patient, encounter, false);
        });
    }
    private Preparation preparation(Context c, UUID patient, UUID encounter, UUID id) {
        return c.jdbc().sql("SELECT * FROM encounter_sign_preparation WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :encounter AND preparation_id = :id")
            .param("practice", c.practiceId()).param("patient", patient).param("encounter", encounter).param("id", id).query((rs, n) -> new Preparation(id,
                rs.getLong("response_revision"), rs.getLong("encounter_version"), rs.getObject("artifact_id", UUID.class), rs.getString("artifact_hash"), rs.getString("release_checksum"), rs.getObject("intervention_set_version",Long.class), rs.getObject("intervention_manifest_id",UUID.class),rs.getObject("clinical_review_id",UUID.class),rs.getString("clinical_review_hash")))
            .optional().orElseThrow(() -> new ClinicalAccessException("SIGN_PREPARATION_UNAVAILABLE", 404));
    }
    private void checkDocuments(Context c, UUID patient, UUID encounter, EncounterInterventionLinks.Manifest manifest) {
        for (var item : manifest.items()) if ("DRAFT".equals(item.summary().clinicalState())
            && item.summary().kind() != EncounterInterventionLinks.Kind.FOLLOW_UP)
            issuer(item.summary().kind()).checkForSigning(c, patient, encounter, item);
    }
    private EncounterSigningDocumentIssuer issuer(EncounterInterventionLinks.Kind kind) {
        return issuers.stream().filter(service -> service.supports(kind)).findFirst()
            .orElseThrow(() -> new ClinicalAccessException("DOCUMENT_ISSUER_UNAVAILABLE", 503));
    }
    private byte[] render(Context c, JdbcEncounterService.Encounter encounter, ph.epr.api.encountertemplates.EncounterInterventionLinks.Manifest manifest,ph.epr.api.clinicalchart.ClinicalLists.ReviewManifest clinicalReview) {
        var fields = new TemplateCompiler().validate(encounter.release().definition());
        var hasCommonFindings = fields.values().stream().anyMatch(field -> "visit.ros".equals(field.concept()));
        var practiceName = practices.findByPracticeId(c.practiceId())
            .orElseThrow(() -> new ClinicalAccessException("PRACTICE_UNAVAILABLE", 503)).displayName();
        var siteName = c.jdbc().sql("SELECT name FROM practice_site WHERE practice_id = :practice AND site_id = :site")
            .param("practice", c.practiceId()).param("site", encounter.siteId()).query(String.class)
            .optional().orElseThrow(() -> new ClinicalAccessException("SITE_UNAVAILABLE", 503));
        var clinicianNames = new HashMap<UUID, String>();
        var html = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Encounter note</title><style>body{font:16px/1.6 sans-serif;max-width:800px;margin:32px auto;padding:24px;color:#192943}h1{font-size:24px}h2{font-size:20px;border-bottom:1px solid #ddd}h3{font-size:16px}p{white-space:pre-wrap;overflow-wrap:anywhere}small{color:#555}</style><body>");
        html.append("<h1>Encounter note</h1><p>Training copy · Not for patient care.</p><p>")
            .append("Practice: ").append(escape(practiceName)).append("<br>Site: ").append(escape(siteName)).append("</p><p>").append(escape(encounter.patient().displayName())).append(" · ").append(escape(encounter.patient().recordNumber())).append("</p><p>Care time: ")
            .append(escape(encounter.clinicalTime().toString())).append("<br>Release: ").append(escape(encounter.release().definition().title())).append(" · ")
            .append(escape(encounter.release().definition().businessVersion())).append("<br>Response revision: ").append(encounter.responseRevision()).append("</p>");
        if (hasCommonFindings) {
            var revisionAuthor = c.jdbc().sql("""
                SELECT actor_id FROM encounter_response_revision
                WHERE practice_id = :practice AND patient_id = :patient AND encounter_id = :encounter AND revision = :revision
                """).param("practice", c.practiceId()).param("patient", encounter.patientId())
                .param("encounter", encounter.encounterId()).param("revision", encounter.responseRevision())
                .query(UUID.class).single();
            html.append("<p>Draft revision recorded by: ").append(escape(clinicianName(c, revisionAuthor, clinicianNames))).append("</p>");
        }
        for (var section : encounter.release().definition().sections()) {
            html.append("<h2>").append(escape(section.label())).append("</h2>");
            for (var field : section.fields()) {
                var sectionIds = new HashSet<UUID>(); sectionIds.add(field.id()); field.fields().forEach(f -> sectionIds.add(f.id()));
                for (var answer : encounter.answers()) if (sectionIds.contains(answer.fieldId())) {
                    var definition = fields.get(answer.fieldId());
                    html.append("<h3>").append(escape(definition.label())).append("</h3>");
                    if (answer.hiddenResolution() == Answer.HiddenResolution.SUPERSEDED) html.append("<p>Superseded: ").append(escape(answer.resolutionReason())).append("</p>");
                    else {
                        String value = answer.missingState() == null ? renderedValue(definition, answer.value()) :
                            answer.missingState() == TemplateDefinition.MissingState.DECLINED
                                && Set.of("visit.ros", "visit.physical-examination").contains(definition.concept()) ? "Patient declined" :
                            answer.missingState().name().replace('_', ' ');
                        html.append("<p>").append(escape(value)).append("</p>");
                        if (answer.hiddenResolution() != null) html.append("<p>Retained as relevant: ").append(escape(answer.resolutionReason())).append("</p>");
                    }
                    html.append("<small>Source: ").append(escape(answer.source().description())).append(" · ").append(escape(answer.review().name())).append("</small>");
                }
            }
        }
        html.append("<h2>Vital signs measured for this encounter</h2>");
        var vitalSets = vitals.list(c, encounter.patientId(), encounter.encounterId());
        if (vitalSets.isEmpty()) html.append("<p>Not recorded.</p>");
        for (var set : vitalSets) {
            var observation = set.current();
            var values = observation.values();
            html.append("<h3>Measured ").append(escape(observation.measuredAt().toString())).append("</h3><p>");
            appendVital(html, "Blood pressure", values.systolicMmhg() == null ? null : values.systolicMmhg() + "/" + values.diastolicMmhg(), "mmHg");
            appendVital(html, "Pulse", values.pulseBpm(), "beats/min");
            appendVital(html, "Respiratory rate", values.respiratoryRateBpm(), "breaths/min");
            appendVital(html, "Temperature", values.temperatureC(), "°C");
            appendVital(html, "Oxygen saturation", values.oxygenSaturationPercent(), "%");
            appendVital(html, "Weight", values.weightKg(), "kg");
            appendVital(html, "Height", values.heightCm(), "cm");
            html.append("</p><small>Source: ").append(escape(observation.source()))
                .append(" · recorded by: ").append(escape(clinicianName(c, observation.recordedBy(), clinicianNames)))
                .append(" · received: ").append(escape(observation.receivedAt().toString()))
                .append(" · record version: ").append(observation.version()).append("</small>");
            if (set.history().size() > 1) html.append("<p>Corrected observation; earlier versions remain in the clinical record.</p>");
        }
        if (encounter.episodeSnapshot() != null) html.append("<h2>Pregnancy source at encounter start</h2><p>").append(escape(encounter.episodeSnapshot().episode().source()))
            .append("</p><p>Selected dating estimate: ").append(escape(encounter.episodeSnapshot().selectedDating() == null || encounter.episodeSnapshot().selectedDating().estimatedDate() == null ? "Not recorded" : encounter.episodeSnapshot().selectedDating().estimatedDate().toString())).append("</p>");
        html.append("<h2>Longitudinal clinical-list review</h2>");
        if(clinicalReview==null)html.append("<p>Clinical-list capability unavailable for this legacy synthetic scope.</p>");
        else html.append("<p>Reviewed ").append(escape(clinicalReview.reviewedAt().toString())).append("</p>");
        html.append("<h2>Interventions at note review</h2>");
        if(manifest.items().isEmpty()) html.append("<p>No interventions recorded at this review.</p>");
        for(var item:manifest.items()) {
            var summary=item.summary();
            var pendingDocument=summary.clinicalState().equals("DRAFT") && summary.kind()!=EncounterInterventionLinks.Kind.FOLLOW_UP;
            html.append("<h3>").append(escape(summary.title())).append("</h3><p>")
                .append(pendingDocument ? "REQUEST INCLUDED IN THIS SIGNATURE" : escape(summary.clinicalState()))
                .append(" · responsible clinician: ").append(escape(clinicianName(c, summary.ownerId(), clinicianNames))).append("</p>");
            for(var detail:summary.details()) html.append("<p>").append(escape(detail)).append("</p>");
            if(pendingDocument) html.append("<p>This document is issued with successful confirmation of this exact encounter signature. Printing and delivery remain separate.</p>");
        }
        html.append("<p>Responsible clinician: ").append(escape(clinicianName(c, encounter.responsibleClinicianId(), clinicianNames))).append("</p><p>Clinical signing assurance remains unapproved. The local synthetic confirmation is bound separately to these exact archived bytes.</p></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void appendVital(StringBuilder html, String label, Object value, String unit) {
        if (value != null) html.append(escape(label)).append(": ").append(escape(value.toString()))
            .append(" ").append(escape(unit)).append("<br>");
    }
    private String renderedValue(TemplateDefinition.Field field, Answer.Value value) {
        if (!value.findings().isEmpty()) {
            var lines = new ArrayList<String>();
            for (var group : field.quickFindings()) for (var finding : value.findings())
                if (group.key().equals(finding.systemKey())) {
                    var details = new ArrayList<>(finding.phrases());
                    if (finding.customText() != null && !finding.customText().isBlank()) details.add(finding.customText());
                    lines.add(group.label() + ": " + String.join(" ", details));
                }
            return String.join("\n", lines);
        }
        if (value.text() != null) return value.text();
        if (value.number() != null) return value.number().toPlainString() + (value.unit() == null ? "" : " " + value.unit());
        if (!value.choices().isEmpty()) return String.join(", ", value.choices());
        if (value.referenceId() != null) return "Linked clinical record";
        return value.items().size() + " history items";
    }
    private String clinicianName(Context c, UUID userId, Map<UUID, String> cache) {
        return cache.computeIfAbsent(userId, id -> identities.clinicianName(c.practiceId(), id)
            .orElseThrow(() -> new ClinicalAccessException("CLINICIAN_IDENTITY_UNAVAILABLE", 503)));
    }
    private String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private ph.epr.api.clinicalchart.ClinicalLists.ReviewManifest clinicalReview(Context c,UUID patient,UUID encounter){
        var summary=clinicalLists.summary(c,patient);
        var readable=java.util.stream.Stream.of(summary.allergies(),summary.medications(),summary.problems()).filter(item->item.actions().read()).count();
        if(readable==0)return null;
        if(readable!=3)throw new ClinicalAccessException("CLINICAL_LIST_RECONCILIATION_ACCESS_REQUIRED",403);
        var review=clinicalLists.reviews(c,patient,encounter).stream().filter(item->item.reviewType().equals("RECONCILIATION")).findFirst()
            .orElseThrow(()->new ClinicalAccessException("ENCOUNTER_RECONCILIATION_REQUIRED",422));
        return clinicalLists.requireCurrentReview(c,patient,review.reviewId());
    }
    private void version(long actual, long expected) { if (actual != expected) throw new ClinicalAccessException("VERSION_CONFLICT", 409); }
    private record AmendmentLink(UUID id, String reason) {}
}
