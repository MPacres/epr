package ph.epr.api.encountertemplates.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import static ph.epr.api.encountertemplates.domain.TemplateDefinition.*;
import static ph.epr.api.encountertemplates.domain.TemplateCompiler.*;

public final class AnswerValidator {
    public enum Stage { DRAFT, SIGN }
    public record Address(UUID fieldId, UUID groupItemId) {}

    public List<String> validate(TemplateDefinition definition, List<Answer> answers, Stage stage) {
        var fields = new TemplateCompiler().validate(definition);
        require(answers.size() <= 6000, "ANSWER_LIMIT");
        var indexed = new HashMap<Address, Answer>();
        var parents = new HashMap<UUID, UUID>();
        fields.values().forEach(field -> field.fields().forEach(child -> parents.put(child.id(), field.id())));
        for (var answer : answers) {
            var field = fields.get(answer.fieldId());
            require(field != null, "UNKNOWN_ANSWER_FIELD");
            require(indexed.put(new Address(answer.fieldId(), answer.groupItemId()), answer) == null, "DUPLICATE_ANSWER");
            require((answer.value() == null) != (answer.missingState() == null), "VALUE_OR_MISSING_REQUIRED");
            require(answer.review() != null && answer.source() != null, "PROVENANCE_REQUIRED");
            require(Set.of("PATIENT_REPORTED", "CLINICIAN_ENTERED", "RECORD_REFERENCE").contains(answer.source().kind()), "SOURCE_KIND");
            text(answer.source().description(), 500);
            if ("RECORD_REFERENCE".equals(answer.source().kind()))
                require(answer.source().recordId() != null && answer.source().version() != null && answer.source().version() >= 0, "SOURCE_REFERENCE");
            if (answer.hiddenResolution() != null) text(answer.resolutionReason(), 500);
            else require(answer.resolutionReason() == null, "ORPHAN_RESOLUTION_REASON");
            if (answer.missingState() != null) require(field.allowedMissingStates().contains(answer.missingState()), "MISSING_STATE_NOT_ALLOWED");
            else validateValue(field, answer.value());
        }
        var itemOwners = new HashMap<UUID, UUID>();
        for (var answer : answers) {
            var field = fields.get(answer.fieldId());
            if (field.type() == Widget.GROUP && answer.value() != null)
                for (var item : answer.value().items()) require(item != null && itemOwners.put(item, field.id()) == null, "DUPLICATE_GROUP_ITEM");
        }
        for (var answer : answers) {
            var parent = parents.get(answer.fieldId());
            require(parent == null ? answer.groupItemId() == null : parent.equals(itemOwners.get(answer.groupItemId())), "ORPHAN_GROUP_ITEM");
        }
        var errors = new ArrayList<String>();
        if (stage == Stage.SIGN) {
            for (var field : fields.values()) {
                var contexts = parents.containsKey(field.id())
                    ? itemOwners.entrySet().stream().filter(e -> e.getValue().equals(parents.get(field.id()))).map(Map.Entry::getKey).toList()
                    : Collections.<UUID>singletonList(null);
                for (var item : contexts) {
                    var answer = indexed.get(new Address(field.id(), item));
                    var visible = evaluate(field.visibleWhen(), indexed, item) == Truth.TRUE;
                    if (parents.containsKey(field.id())) visible &= evaluate(fields.get(parents.get(field.id())).visibleWhen(), indexed, null) == Truth.TRUE;
                    boolean required = field.requiredAtSign() || field.requiredWhen() != null && evaluate(field.requiredWhen(), indexed, item) == Truth.TRUE;
                    var path = field.id() + (item == null ? "" : "/" + item);
                    if (required && answer == null) errors.add(path + ":REQUIRED");
                    if (answer != null && !visible && answer.hiddenResolution() == null) errors.add(path + ":HIDDEN_ANSWER_REVIEW");
                    if (answer != null && answer.review() != Answer.Review.REVIEWED) errors.add(path + ":SOURCE_REVIEW");
                }
            }
        }
        return List.copyOf(errors);
    }

    private void validateValue(Field field, Answer.Value value) {
        boolean text = false, number = false, unit = false, choices = false, reference = false, items = false;
        switch (field.type()) {
            case TEXT, MULTILINE_TEXT -> { text(value.text(), field.maxLength()); text = true; }
            case DATE -> { text(value.text(), 10); try { LocalDate.parse(value.text()); } catch (RuntimeException e) { throw new TemplateValidationException("INVALID_DATE"); } text = true; }
            case DATETIME -> { text(value.text(), 40); try { OffsetDateTime.parse(value.text()); } catch (RuntimeException e) { throw new TemplateValidationException("INVALID_DATETIME"); } text = true; }
            case INTEGER, DECIMAL, QUANTITY -> {
                require(value.number() != null && value.number().precision() <= 18
                    && Math.max(0, value.number().stripTrailingZeros().scale()) <= (field.type() == Widget.INTEGER ? 0 : field.precision()), "INVALID_NUMBER");
                number = true;
                if (field.type() == Widget.QUANTITY) { require(value.unit() != null && field.units().contains(value.unit()), "INVALID_UNIT"); unit = true; }
            }
            case SINGLE_CHOICE, ASSESSMENT_STATE, MULTIPLE_CHOICE -> {
                require(!value.choices().isEmpty() && field.options().containsAll(value.choices())
                    && new HashSet<>(value.choices()).size() == value.choices().size()
                    && (field.type() == Widget.MULTIPLE_CHOICE || value.choices().size() == 1), "INVALID_CHOICE"); choices = true;
            }
            case REFERENCE -> { require(value.referenceId() != null && value.referenceVersion() != null && value.referenceVersion() >= 0, "INVALID_REFERENCE"); reference = true; }
            case GROUP -> { require(value.items().size() <= field.maxItems(), "GROUP_LIMIT"); items = true; }
        }
        require((text || value.text() == null) && (number || value.number() == null) && (unit || value.unit() == null)
            && (choices || value.choices().isEmpty()) && (reference || value.referenceId() == null && value.referenceVersion() == null)
            && (items || value.items().isEmpty()), "UNEXPECTED_VALUE_MEMBER");
    }

    public Truth evaluate(Rule rule, Map<Address, Answer> answers, UUID item) {
        if (rule == null) return Truth.TRUE;
        if (rule.op() == Operator.NOT) {
            return switch (evaluate(rule.rules().getFirst(), answers, item)) { case TRUE -> Truth.FALSE; case FALSE -> Truth.TRUE; case UNKNOWN -> Truth.UNKNOWN; };
        }
        if (rule.op() == Operator.ALL || rule.op() == Operator.ANY) {
            var results = rule.rules().stream().map(child -> evaluate(child, answers, item)).toList();
            if (rule.op() == Operator.ALL && results.contains(Truth.FALSE)) return Truth.FALSE;
            if (rule.op() == Operator.ANY && results.contains(Truth.TRUE)) return Truth.TRUE;
            if (results.contains(Truth.UNKNOWN)) return Truth.UNKNOWN;
            return rule.op() == Operator.ALL ? Truth.TRUE : Truth.FALSE;
        }
        var answer = answers.get(new Address(rule.fieldId(), item));
        if (answer == null) answer = answers.get(new Address(rule.fieldId(), null));
        if (answer == null || answer.missingState() != null) return Truth.UNKNOWN;
        if (rule.op() == Operator.PRESENT) return Truth.TRUE;
        return answer.value().choices().stream().anyMatch(rule.values()::contains) ? Truth.TRUE : Truth.FALSE;
    }
}
