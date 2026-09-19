package ph.epr.api.encountertemplates.infrastructure;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import ph.epr.api.encountertemplates.domain.*;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
public class JdbcTemplateRegistry {
    private final TenantCommandJournal journal;
    private final TemplateCompiler compiler = new TemplateCompiler();
    public JdbcTemplateRegistry(TenantCommandJournal journal) { this.journal = journal; }
    public record Draft(UUID draftId, TemplateDefinition definition, String checksum, long version,
                        String changeSummary, String approvedChecksum, UUID approvedBy, OffsetDateTime approvedAt, String approvalBasis) {}
    public record DraftCommand(UUID draftId, long expectedVersion, TemplateDefinition definition, String changeSummary) {}
    public record Approval(long expectedVersion, String checksum, String basis) {}
    public record Publication(UUID releaseId, long expectedVersion) {}
    public record Availability(long expectedVersion, boolean retired, String reason) {}
    public record Release(UUID releaseId, TemplateDefinition definition, String checksum, UUID approvedBy,
                          OffsetDateTime approvedAt, String approvalBasis, UUID publishedBy, OffsetDateTime publishedAt,
                          boolean retired, long availabilityVersion) {}

    private void validate(TemplateDefinition definition) {
        compiler.validate(definition);
        if (!definition.syntheticOnly()) throw new ClinicalAccessException("CLINICAL_CONTENT_APPROVAL_NOT_ENABLED", 422);
    }
    public Draft draft(Context c, UUID id) {
        return c.jdbc().sql("SELECT * FROM encounter_template_draft WHERE practice_id = :practice AND draft_id = :id")
            .param("practice", c.practiceId()).param("id", id).query((rs, n) -> new Draft(id,
                ClinicalJson.read(rs.getString("definition"), TemplateDefinition.class), rs.getString("checksum"), rs.getLong("version"),
                rs.getString("change_summary"), rs.getString("approved_checksum"), rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", OffsetDateTime.class), rs.getString("approval_basis")))
            .optional().orElseThrow(() -> new ClinicalAccessException("TEMPLATE_DRAFT_UNAVAILABLE", 404));
    }
    public Draft saveDraft(Context c, UUID key, DraftCommand request, boolean create) {
        validate(request.definition()); text(request.changeSummary());
        if (request.draftId() == null) throw new ClinicalAccessException("INVALID_DRAFT", 422);
        return journal.execute(c, (create ? "create-template/" : "edit-template/") + request.draftId(), key, request, Draft.class, () -> {
            var checksum = ClinicalJson.hash(request.definition());
            if (create) {
                if (request.expectedVersion() != -1) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
                c.jdbc().sql("""
                    INSERT INTO encounter_template_draft(practice_id, draft_id, definition, checksum, change_summary, created_by)
                    VALUES (:practice, :id, CAST(:definition AS jsonb), :checksum, :summary, :actor)
                    """).param("practice", c.practiceId()).param("id", request.draftId()).param("definition", ClinicalJson.write(request.definition()))
                    .param("checksum", checksum).param("summary", request.changeSummary()).param("actor", c.actorId()).update();
            } else {
                var updated = c.jdbc().sql("""
                    UPDATE encounter_template_draft SET definition = CAST(:definition AS jsonb), checksum = :checksum,
                        change_summary = :summary, version = version + 1,
                        approved_checksum = NULL, approved_by = NULL, approved_at = NULL, approval_basis = NULL
                    WHERE practice_id = :practice AND draft_id = :id AND version = :version
                    """).param("practice", c.practiceId()).param("id", request.draftId()).param("version", request.expectedVersion())
                    .param("definition", ClinicalJson.write(request.definition())).param("checksum", checksum).param("summary", request.changeSummary()).update();
                if (updated != 1) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            }
            journal.audit(c, request.draftId(), create ? "TEMPLATE_DRAFT_CREATED" : "TEMPLATE_DRAFT_CHANGED", request.changeSummary());
            return draft(c, request.draftId());
        });
    }
    public Draft approve(Context c, UUID id, UUID key, Approval request) {
        text(request.basis());
        return journal.execute(c, "approve-template/" + id, key, request, Draft.class, () -> {
            var value = draft(c, id); validate(value.definition());
            if (!value.checksum().equals(request.checksum())) throw new ClinicalAccessException("CHECKSUM_CONFLICT", 409);
            var updated = c.jdbc().sql("""
                UPDATE encounter_template_draft SET approved_checksum = checksum, approved_by = :actor,
                    approved_at = CURRENT_TIMESTAMP, approval_basis = :basis, version = version + 1
                WHERE practice_id = :practice AND draft_id = :id AND version = :version AND checksum = :checksum
                """).param("actor", c.actorId()).param("basis", "SYNTHETIC ONLY: " + request.basis()).param("practice", c.practiceId())
                .param("id", id).param("version", request.expectedVersion()).param("checksum", request.checksum()).update();
            if (updated != 1) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            journal.audit(c, id, "SYNTHETIC_TEMPLATE_APPROVED", request.basis());
            return draft(c, id);
        });
    }
    public Release publish(Context c, UUID id, UUID key, Publication request) {
        if (request.releaseId() == null) throw new ClinicalAccessException("INVALID_RELEASE", 422);
        return journal.execute(c, "publish-template/" + id, key, request, Release.class, () -> {
            c.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", c.practiceId() + "/template-publication").query((rs, n) -> true).single();
            c.jdbc().sql("SELECT draft_id FROM encounter_template_draft WHERE practice_id = :practice AND draft_id = :id FOR UPDATE")
                .param("practice", c.practiceId()).param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> new ClinicalAccessException("TEMPLATE_DRAFT_UNAVAILABLE", 404));
            var value = draft(c, id); validate(value.definition());
            if (value.version() != request.expectedVersion()) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            if (value.approvedBy() == null || !value.checksum().equals(value.approvedChecksum())) throw new ClinicalAccessException("TEMPLATE_APPROVAL_REQUIRED", 409);
            verifyPermanentFields(c, value.definition());
            c.jdbc().sql("""
                INSERT INTO encounter_template_release(practice_id, release_id, draft_id, template_key, business_version,
                    definition, checksum, approved_by, approved_at, approval_basis, published_by)
                VALUES (:practice, :release, :draft, :key, :version, CAST(:definition AS jsonb), :checksum, :approver, :approved, :basis, :actor)
                """).param("practice", c.practiceId()).param("release", request.releaseId()).param("draft", id)
                .param("key", value.definition().templateKey()).param("version", value.definition().businessVersion())
                .param("definition", ClinicalJson.write(value.definition())).param("checksum", value.checksum())
                .param("approver", value.approvedBy()).param("approved", value.approvedAt()).param("basis", value.approvalBasis()).param("actor", c.actorId()).update();
            c.jdbc().sql("INSERT INTO encounter_template_availability(practice_id, release_id, site_id) VALUES (:practice, :release, :site)")
                .param("practice", c.practiceId()).param("release", request.releaseId()).param("site", c.siteId()).update();
            journal.audit(c, request.releaseId(), "TEMPLATE_PUBLISHED", null);
            return release(c, request.releaseId(), false);
        });
    }
    private void verifyPermanentFields(Context c, TemplateDefinition definition) {
        var fields = compiler.validate(definition);
        for (var field : fields.values()) {
            var previous = c.jdbc().sql("SELECT meaning::text FROM encounter_field_identity WHERE practice_id = :practice AND field_id = :id")
                .param("practice", c.practiceId()).param("id", field.id()).query(String.class).optional();
            if (previous.isPresent()) compiler.requireCompatibleMeaning(ClinicalJson.read(previous.get(), TemplateDefinition.Field.class), field);
            else c.jdbc().sql("INSERT INTO encounter_field_identity(practice_id, field_id, meaning) VALUES (:practice, :id, CAST(:meaning AS jsonb))")
                .param("practice", c.practiceId()).param("id", field.id()).param("meaning", ClinicalJson.write(field)).update();
        }
        var previousReleases = c.jdbc().sql("SELECT definition::text FROM encounter_template_release WHERE practice_id = :practice AND template_key = :key")
            .param("practice", c.practiceId()).param("key", definition.templateKey()).query(String.class).list();
        for (var previous : previousReleases) {
            var previousDefinition = ClinicalJson.read(previous, TemplateDefinition.class);
            for (var field : compiler.validate(previousDefinition).values()) {
                if (field.requiredAtSign() && (!fields.containsKey(field.id()) || !fields.get(field.id()).requiredAtSign()))
                    throw new TemplateValidationException("REQUIREMENT_WEAKENED");
            }
        }
    }
    public List<Release> catalog(Context c, String specialty, String encounterType) {
        return c.jdbc().sql("""
            SELECT r.release_id, r.definition::text FROM encounter_template_release r JOIN encounter_template_availability a USING(practice_id, release_id)
            WHERE r.practice_id = :practice AND a.site_id = :site AND NOT a.retired
                AND (:specialty IS NULL OR r.definition->>'specialty' = :specialty)
                AND (:type IS NULL OR r.definition->>'encounterType' = :type)
            ORDER BY r.published_at DESC
            """).param("practice", c.practiceId()).param("site", c.siteId()).param("specialty", specialty, Types.VARCHAR)
            .param("type", encounterType, Types.VARCHAR)
            .query((rs, n) -> new CatalogCandidate(rs.getObject(1, UUID.class), ClinicalJson.read(rs.getString(2), TemplateDefinition.class))).list().stream()
            .filter(candidate -> c.reproductiveAccess() || compiler.validate(candidate.definition()).values().stream()
                .noneMatch(field -> field.sensitivity() == TemplateDefinition.Sensitivity.RESTRICTED_REPRODUCTIVE))
            .map(candidate -> release(c, candidate.id(), false)).toList();
    }
    public Release release(Context c, UUID id, boolean forSelection) {
        var value = c.jdbc().sql("""
            SELECT r.*, a.retired, a.version AS availability_version FROM encounter_template_release r
            JOIN encounter_template_availability a USING(practice_id, release_id)
            WHERE r.practice_id = :practice AND r.release_id = :id AND a.site_id = :site
            """ + (forSelection ? " FOR SHARE OF a" : ""))
            .param("practice", c.practiceId()).param("id", id).param("site", c.siteId()).query((rs, n) -> new Release(id,
                ClinicalJson.read(rs.getString("definition"), TemplateDefinition.class), rs.getString("checksum"), rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", OffsetDateTime.class), rs.getString("approval_basis"), rs.getObject("published_by", UUID.class),
                rs.getObject("published_at", OffsetDateTime.class), rs.getBoolean("retired"), rs.getLong("availability_version")))
            .optional().orElseThrow(() -> new ClinicalAccessException("TEMPLATE_UNAVAILABLE", 404));
        if (!ClinicalJson.hash(value.definition()).equals(value.checksum())) throw new ClinicalAccessException("TEMPLATE_INTEGRITY_FAILURE", 503);
        validate(value.definition());
        if (forSelection && value.retired()) throw new ClinicalAccessException("TEMPLATE_RETIRED", 409);
        if (!c.reproductiveAccess() && compiler.validate(value.definition()).values().stream()
            .anyMatch(f -> f.sensitivity() == TemplateDefinition.Sensitivity.RESTRICTED_REPRODUCTIVE))
            throw new ClinicalAccessException("REPRODUCTIVE_ACCESS_DENIED", 403);
        return value;
    }
    public Release availability(Context c, UUID id, UUID key, Availability request) {
        text(request.reason());
        return journal.execute(c, "template-availability/" + id + "/" + c.siteId(), key, request, Release.class, () -> {
            var updated = c.jdbc().sql("""
                UPDATE encounter_template_availability SET retired = :retired, version = version + 1
                WHERE practice_id = :practice AND release_id = :id AND site_id = :site AND version = :version
                """).param("retired", request.retired()).param("practice", c.practiceId()).param("id", id).param("site", c.siteId())
                .param("version", request.expectedVersion()).update();
            if (updated != 1) throw new ClinicalAccessException("VERSION_CONFLICT", 409);
            journal.audit(c, id, request.retired() ? "TEMPLATE_RETIRED" : "TEMPLATE_RESTORED", request.reason());
            return release(c, id, false);
        });
    }
    private record CatalogCandidate(UUID id, TemplateDefinition definition) {}
    private void text(String value) { if (value == null || value.isBlank() || value.length() > 470) throw new ClinicalAccessException("REASON_REQUIRED", 422); }
}
