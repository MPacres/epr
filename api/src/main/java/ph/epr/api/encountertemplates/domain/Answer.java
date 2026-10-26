package ph.epr.api.encountertemplates.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ph.epr.api.encountertemplates.domain.TemplateDefinition.MissingState;

/** Authorship/receipt are supplied by the response revision, never accepted from this envelope. */
public record Answer(UUID fieldId, UUID groupItemId, Value value, MissingState missingState,
                     Source source, Review review, HiddenResolution hiddenResolution, String resolutionReason) {
    public enum Review { NOT_REVIEWED, REVIEWED }
    public enum HiddenResolution { RETAIN_RELEVANT, SUPERSEDED }
    public record Source(String kind, UUID recordId, Long version, OffsetDateTime clinicalTime, String description) {}
    public record Value(String text, BigDecimal number, String unit, List<String> choices,
                        UUID referenceId, Long referenceVersion, List<UUID> items,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Finding> findings) {
        public Value {
            choices = List.copyOf(choices);
            items = List.copyOf(items);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
        public Value(String text, BigDecimal number, String unit, List<String> choices,
                     UUID referenceId, Long referenceVersion, List<UUID> items) {
            this(text, number, unit, choices, referenceId, referenceVersion, items, List.of());
        }
    }
    /** A clinician-selected system and its exact phrases or individually authored observation. */
    public record Finding(String systemKey, List<String> phrases, String customText) {
        public Finding { phrases = List.copyOf(phrases); }
    }
}
