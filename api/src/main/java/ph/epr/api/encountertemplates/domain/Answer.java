package ph.epr.api.encountertemplates.domain;

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
                        UUID referenceId, Long referenceVersion, List<UUID> items) {
        public Value { choices = List.copyOf(choices); items = List.copyOf(items); }
    }
}
