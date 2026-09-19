package ph.epr.api.encountertemplates.domain;

import java.util.List;
import java.util.UUID;

/** Versioned, non-executable application contract. No clinical defaults or implicit conversions. */
public record TemplateDefinition(
    String schemaVersion, String ruleVersion, String rendererVersion, String extractionVersion,
    String terminologyVersion, String templateKey, String title, String specialty,
    String encounterType, String businessVersion, boolean requiresPregnancyEpisode,
    boolean syntheticOnly, List<Section> sections
) {
    public TemplateDefinition { sections = List.copyOf(sections); }

    public record Section(UUID id, String key, String label, List<Field> fields) {
        public Section { fields = List.copyOf(fields); }
    }

    public record Field(
        UUID id, String key, String concept, String label, Widget type,
        List<String> options, List<String> units, List<MissingState> allowedMissingStates,
        int maxLength, int maxItems, int precision, boolean requiredAtSign,
        Sensitivity sensitivity, Rule visibleWhen, Rule requiredWhen, List<Field> fields
    ) {
        public Field {
            options = List.copyOf(options);
            units = List.copyOf(units);
            allowedMissingStates = List.copyOf(allowedMissingStates);
            fields = List.copyOf(fields);
        }
    }

    public enum Widget { TEXT, MULTILINE_TEXT, INTEGER, DECIMAL, QUANTITY, DATE, DATETIME,
        SINGLE_CHOICE, MULTIPLE_CHOICE, ASSESSMENT_STATE, REFERENCE, GROUP }
    public enum MissingState { NOT_ASSESSED, UNKNOWN, DECLINED, NOT_APPLICABLE }
    public enum Sensitivity { CLINICAL, RESTRICTED_REPRODUCTIVE }
    public enum Operator { EQ, IN, PRESENT, ALL, ANY, NOT }
    public enum Truth { TRUE, FALSE, UNKNOWN }

    /** Only choice-code comparisons are supported in rule version 1; no clinical thresholds. */
    public record Rule(Operator op, UUID fieldId, List<String> values, List<Rule> rules) {
        public Rule { values = List.copyOf(values); rules = List.copyOf(rules); }
    }
}
