package ph.epr.api.encountertemplates.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ph.epr.api.encountertemplates.domain.TemplateDefinition;
import ph.epr.api.identitytenancy.AuthenticatedActor;
import ph.epr.api.platformoperations.ClinicalJson;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.platformoperations.TenantCommandJournal;

/** Publishes the provisional synthetic encounter pack into one explicitly authorized site. */
@Component
@Profile("local")
class LocalSyntheticTemplatePack {
    static final List<String> TEMPLATE_KEYS = List.of(
        "general.outpatient",
        "obgyn.prenatal.initial",
        "obgyn.prenatal.followup",
        "obgyn.gynecology.initial",
        "obgyn.gynecology.followup",
        "obgyn.postpartum.followup"
    );

    private final TenantTransactions tenants;
    private final JdbcTemplateRegistry templates;
    private final TenantCommandJournal journal;

    LocalSyntheticTemplatePack(TenantTransactions tenants, JdbcTemplateRegistry templates, TenantCommandJournal journal) {
        this.tenants = tenants;
        this.templates = templates;
        this.journal = journal;
    }

    List<JdbcTemplateRegistry.Release> seed(AuthenticatedActor actor, UUID practice, UUID site) {
        return tenants.execute(actor, practice, site, true, context -> {
            // Serialize the complete pack across sites and actors, and roll it back as one unit.
            context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", practice + "/synthetic-template-pack").query((rs, n) -> true).single();
            return TEMPLATE_KEYS.stream().map(key -> {
                var definition = definition(key);
                var releaseId = id(key + "/release");
                var existing = context.jdbc().sql("""
                    SELECT checksum FROM encounter_template_release
                    WHERE practice_id = :practice AND release_id = :release
                    """).param("practice", practice).param("release", releaseId).query(String.class).optional();
                if (existing.isPresent()) {
                    if (!existing.get().equals(ClinicalJson.hash(definition)))
                        throw new IllegalStateException("Published synthetic fixture differs; publish a new version: " + key);
                    // A release is immutable and Practice-wide; only availability is site-specific.
                    // DO NOTHING deliberately preserves an explicit retirement at this site.
                    var added = context.jdbc().sql("""
                        INSERT INTO encounter_template_availability(practice_id, release_id, site_id)
                        VALUES (:practice, :release, :site) ON CONFLICT DO NOTHING
                        """).param("practice", practice).param("release", releaseId).param("site", site).update();
                    if (added == 1) journal.audit(context, releaseId, "SYNTHETIC_TEMPLATE_SITE_SEEDED",
                        "Opt-in local synthetic template pack");
                    return templates.release(context, releaseId, false);
                }
                var draft = templates.saveDraft(context, id(key + "/create"),
                    new JdbcTemplateRegistry.DraftCommand(id(key + "/draft"), -1, definition,
                        "Provisional synthetic field pack"), true);
                var approved = templates.approve(context, draft.draftId(), id(key + "/approve"),
                    new JdbcTemplateRegistry.Approval(draft.version(), draft.checksum(),
                        "Engineering fixture; no clinical approval or real-patient issuance"));
                return templates.publish(context, draft.draftId(), id(key + "/publish"),
                    new JdbcTemplateRegistry.Publication(releaseId, approved.version()));
            }).toList();
        });
    }

    private TemplateDefinition definition(String key) {
        try (var stream = getClass().getResourceAsStream("/encounter-templates/synthetic/" + key + ".json")) {
            if (stream == null) throw new IllegalStateException("Synthetic template fixture is missing: " + key);
            return ClinicalJson.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), TemplateDefinition.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic template fixture could not be loaded: " + key, exception);
        }
    }

    private UUID id(String key) {
        return UUID.nameUUIDFromBytes(("epr-synthetic-obgyn/" + key).getBytes(StandardCharsets.UTF_8));
    }
}
