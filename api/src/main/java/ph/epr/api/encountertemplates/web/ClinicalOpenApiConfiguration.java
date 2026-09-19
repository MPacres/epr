package ph.epr.api.encountertemplates.web;

import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.ComposedSchema;

/** Record components with meaningful null states must be represented as such in OpenAPI 3.1. */
@Configuration(proxyBeanMethods = false)
class ClinicalOpenApiConfiguration {
    private Schema<?> nullSchema() { var schema = new Schema<>(); schema.setTypes(Set.of("null")); return schema; }
    @Bean OpenApiCustomizer clinicalNullStates() {
        var nullable = Map.ofEntries(
            Map.entry("PrescriptionRecord", List.of("authorizedAt", "artifactId", "artifactHash", "supersedesId")),
            Map.entry("ServiceRequestRecord", List.of("authorizedAt", "artifactId", "artifactHash", "supersedesId")),
            Map.entry("ReferralRecord", List.of("authorizedAt", "artifactId", "artifactHash", "supersedesId")),
            Map.entry("PrescriptionData", List.of("reviewId")),
            Map.entry("ServiceRequestData", List.of("groupId", "requestedDate")),
            Map.entry("Summary", List.of("dueDate", "artifactId", "artifactHash", "groupId")),
            Map.entry("InterventionWork", List.of("proposedOwnerId", "dueDate")),
            Map.entry("IncomingReport", List.of("correctsId", "patientId", "requestId")),
            Map.entry("Entry", List.of("encounterId")),
            Map.entry("QueueStart", List.of("episodeId")),
            Map.entry("Task", List.of("dueDate", "acknowledgedAt")),
            Map.entry("Signature", List.of("amendmentOf", "amendmentReason")),
            Map.entry("Answer", List.of("groupItemId", "value", "missingState", "hiddenResolution", "resolutionReason")),
            Map.entry("Value", List.of("text", "number", "unit", "referenceId", "referenceVersion")),
            Map.entry("Source", List.of("recordId", "version", "clinicalTime")),
            Map.entry("Field", List.of("visibleWhen", "requiredWhen")),
            Map.entry("Rule", List.of("fieldId")),
            Map.entry("Patient", List.of("birthDate")),
            Map.entry("Episode", List.of("clinicalTime", "selectedDatingId")),
            Map.entry("DatingAssessment", List.of("estimatedDate", "sourceDate")),
            Map.entry("Snapshot", List.of("selectedDating")),
            Map.entry("Encounter", List.of("episodeSnapshot", "endedAt", "savedAt", "sessionId", "queueEntryId")),
            Map.entry("Draft", List.of("approvedChecksum", "approvedBy", "approvedAt", "approvalBasis")),
            Map.entry("Change", List.of("groupItemId", "answer")),
            Map.entry("Create", List.of("episodeId")),
            Map.entry("CreateEpisode", List.of("clinicalTime")),
            Map.entry("Registration", List.of("birthDate")),
            Map.entry("AddDating", List.of("estimatedDate", "sourceDate"))
        );
        return api -> {
            if (api.getComponents() == null) return;
            for (var entry : nullable.entrySet()) {
                var schema = api.getComponents().getSchemas().get(entry.getKey());
                if (schema == null || schema.getProperties() == null) continue;
                for (var name : entry.getValue()) {
                    var property = (Schema<?>) schema.getProperties().get(name);
                    if (property != null) schema.addProperty(name, new ComposedSchema().addAnyOfItem(property).addAnyOfItem(nullSchema()));
                }
            }
        };
    }
}
