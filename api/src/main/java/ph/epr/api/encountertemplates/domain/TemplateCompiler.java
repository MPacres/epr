package ph.epr.api.encountertemplates.domain;

import java.util.*;
import static ph.epr.api.encountertemplates.domain.TemplateDefinition.*;

/** Bounded compiler shared by import, draft validation and publication. */
public final class TemplateCompiler {
    public record ModuleVersion(UUID id, List<UUID> dependencies, List<Section> sections) {
        public ModuleVersion { dependencies = List.copyOf(dependencies); sections = List.copyOf(sections); }
    }

    public TemplateDefinition compose(TemplateDefinition metadata, List<ModuleVersion> modules) {
        require(modules.size() > 0 && modules.size() <= 20, "MODULE_LIMIT");
        var byId = new LinkedHashMap<UUID, ModuleVersion>();
        for (var module : modules) require(module.id() != null && byId.put(module.id(), module) == null, "DUPLICATE_MODULE");
        var ordered = new ArrayList<Section>();
        var done = new HashSet<UUID>();
        for (var module : modules) flatten(module.id(), byId, new HashSet<>(), done, ordered);
        var result = new TemplateDefinition(metadata.schemaVersion(), metadata.ruleVersion(), metadata.rendererVersion(),
            metadata.extractionVersion(), metadata.terminologyVersion(), metadata.templateKey(), metadata.title(),
            metadata.specialty(), metadata.encounterType(), metadata.businessVersion(), metadata.requiresPregnancyEpisode(),
            metadata.syntheticOnly(), ordered);
        validate(result);
        return result;
    }

    private void flatten(UUID id, Map<UUID, ModuleVersion> modules, Set<UUID> visiting,
                         Set<UUID> done, List<Section> sections) {
        if (done.contains(id)) return;
        require(visiting.add(id), "CYCLIC_MODULES");
        var module = modules.get(id);
        require(module != null, "UNRESOLVED_MODULE");
        for (var dependency : module.dependencies()) flatten(dependency, modules, visiting, done, sections);
        sections.addAll(module.sections());
        visiting.remove(id);
        done.add(id);
    }

    public Map<UUID, Field> validate(TemplateDefinition definition) {
        require(definition != null, "MISSING_DEFINITION");
        require("1.0".equals(definition.schemaVersion()) && "1".equals(definition.ruleVersion())
            && "1".equals(definition.rendererVersion()) && "none".equals(definition.extractionVersion())
            && "uncoded-1".equals(definition.terminologyVersion()), "UNSUPPORTED_VERSION");
        text(definition.templateKey(), 100); text(definition.title(), 160);
        text(definition.specialty(), 80); text(definition.encounterType(), 80);
        require(definition.businessVersion() != null && definition.businessVersion().matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"), "BUSINESS_VERSION");
        require(!definition.sections().isEmpty() && definition.sections().size() <= 30, "SECTION_LIMIT");
        var identities = new HashSet<UUID>();
        var fields = new LinkedHashMap<UUID, Field>();
        var parents = new HashMap<UUID, UUID>();
        var sectionKeys = new HashSet<String>();
        for (var section : definition.sections()) {
            require(section.id() != null && identities.add(section.id()), "DUPLICATE_ID");
            text(section.key(), 100); text(section.label(), 160);
            require(sectionKeys.add(section.key()) && !section.fields().isEmpty(), "SECTION_FIELDS");
            collect(section.fields(), null, identities, fields, parents, 0);
        }
        require(fields.size() <= 200, "FIELD_LIMIT");
        var graph = new HashMap<UUID, Set<UUID>>();
        for (var field : fields.values()) {
            var dependencies = new HashSet<UUID>();
            validateRule(field.visibleWhen(), field.id(), fields, parents, dependencies, 0);
            validateRule(field.requiredWhen(), field.id(), fields, parents, dependencies, 0);
            require(!field.requiredAtSign() || field.visibleWhen() == null, "HIDDEN_MANDATORY_FIELD");
            require(field.visibleWhen() == null || field.requiredWhen() == null
                || field.visibleWhen().equals(field.requiredWhen()), "CONTRADICTORY_REQUIREMENT");
            // Required descendants of a hidden group would otherwise be impossible to satisfy visibly.
            var parent = parents.get(field.id());
            require(parent == null || fields.get(parent).visibleWhen() == null || !field.requiredAtSign(), "HIDDEN_MANDATORY_FIELD");
            graph.put(field.id(), dependencies);
        }
        var done = new HashSet<UUID>();
        for (var id : fields.keySet()) cycle(id, graph, new HashSet<>(), done);
        return Collections.unmodifiableMap(fields);
    }

    private void collect(List<Field> source, UUID parent, Set<UUID> identities,
                         Map<UUID, Field> fields, Map<UUID, UUID> parents, int depth) {
        require(depth <= 1 && source.size() <= 100, "NESTING_LIMIT");
        var keys = new HashSet<String>();
        for (var field : source) {
            require(field.id() != null && identities.add(field.id()), "DUPLICATE_ID");
            text(field.key(), 100); text(field.concept(), 160); text(field.label(), 160);
            require(keys.add(field.key()) && field.type() != null && field.sensitivity() != null, "INVALID_FIELD");
            require(field.maxLength() >= 1 && field.maxLength() <= 10000, "LENGTH_LIMIT");
            require(field.precision() >= 0 && field.precision() <= 6, "PRECISION_LIMIT");
            require(field.allowedMissingStates().size() == new HashSet<>(field.allowedMissingStates()).size(), "DUPLICATE_MISSING_STATE");
            require(field.options().size() <= 100 && new HashSet<>(field.options()).size() == field.options().size(), "OPTION_LIMIT");
            field.options().forEach(option -> text(option, 120));
            require(field.units().size() <= 20 && new HashSet<>(field.units()).size() == field.units().size(), "UNIT_LIMIT");
            field.units().forEach(unit -> text(unit, 30));
            boolean choice = Set.of(Widget.SINGLE_CHOICE, Widget.MULTIPLE_CHOICE, Widget.ASSESSMENT_STATE).contains(field.type());
            require(choice == !field.options().isEmpty(), "CHOICE_OPTIONS");
            require(field.quickFindings().isEmpty() || field.type() == Widget.MULTILINE_TEXT
                && Set.of("visit.ros", "visit.physical-examination").contains(field.concept()), "QUICK_FINDING_FIELD");
            require(field.quickFindings().size() <= 20, "QUICK_FINDING_LIMIT");
            var quickKeys = new HashSet<String>();
            for (var group : field.quickFindings()) {
                text(group.key(), 60); text(group.label(), 80);
                require(group.key().matches("[a-z][a-z0-9.-]*") && quickKeys.add(group.key())
                    && !group.phrases().isEmpty() && group.phrases().size() <= 12
                    && new HashSet<>(group.phrases()).size() == group.phrases().size(), "QUICK_FINDING_GROUP");
                group.phrases().forEach(phrase -> text(phrase, 220));
            }
            require((field.type() == Widget.QUANTITY) == !field.units().isEmpty(), "QUANTITY_UNITS");
            require((field.type() == Widget.GROUP) == !field.fields().isEmpty(), "GROUP_FIELDS");
            require(field.type() == Widget.GROUP ? field.maxItems() >= 1 && field.maxItems() <= 30 : field.maxItems() == 0, "GROUP_LIMIT");
            require(parent == null || field.type() != Widget.GROUP, "NESTING_LIMIT");
            fields.put(field.id(), field);
            if (parent != null) parents.put(field.id(), parent);
            if (field.type() == Widget.GROUP) collect(field.fields(), field.id(), identities, fields, parents, depth + 1);
        }
    }

    private void validateRule(Rule rule, UUID target, Map<UUID, Field> fields,
                              Map<UUID, UUID> parents, Set<UUID> dependencies, int depth) {
        if (rule == null) return;
        require(depth <= 6 && rule.op() != null && rule.rules().size() <= 10 && rule.values().size() <= 100, "RULE_LIMIT");
        if (Set.of(Operator.ALL, Operator.ANY, Operator.NOT).contains(rule.op())) {
            require(rule.fieldId() == null && rule.values().isEmpty() && !rule.rules().isEmpty()
                && (rule.op() != Operator.NOT || rule.rules().size() == 1), "INVALID_LOGICAL_RULE");
            for (var child : rule.rules()) validateRule(child, target, fields, parents, dependencies, depth + 1);
        } else {
            var source = fields.get(rule.fieldId());
            require(source != null && source.type() != Widget.GROUP && rule.rules().isEmpty(), "UNRESOLVED_RULE_FIELD");
            require(parents.get(source.id()) == null || Objects.equals(parents.get(source.id()), parents.get(target)), "AMBIGUOUS_GROUP_RULE");
            require(source.sensitivity().ordinal() <= fields.get(target).sensitivity().ordinal(), "SENSITIVE_RULE_INFERENCE");
            if (rule.op() == Operator.PRESENT) require(rule.values().isEmpty(), "INVALID_PRESENCE_RULE");
            else {
                require(Set.of(Widget.SINGLE_CHOICE, Widget.ASSESSMENT_STATE).contains(source.type()), "RULE_TYPE");
                require(!rule.values().isEmpty() && source.options().containsAll(rule.values())
                    && (rule.op() != Operator.EQ || rule.values().size() == 1), "RULE_OPTIONS");
            }
            dependencies.add(source.id());
        }
    }

    private void cycle(UUID id, Map<UUID, Set<UUID>> graph, Set<UUID> visiting, Set<UUID> done) {
        if (done.contains(id)) return;
        require(visiting.add(id), "CYCLIC_RULES");
        for (var dependency : graph.get(id)) cycle(dependency, graph, visiting, done);
        visiting.remove(id); done.add(id);
    }

    /** Permanent IDs may gain presentation changes, but cannot be recycled for another meaning. */
    public void requireCompatibleMeaning(Field previous, Field next) {
        require(previous.id().equals(next.id()) && previous.concept().equals(next.concept())
            && previous.type() == next.type() && previous.units().equals(next.units())
            && previous.options().equals(next.options()) && previous.sensitivity() == next.sensitivity(), "FIELD_MEANING_CHANGED");
        require(!previous.requiredAtSign() || next.requiredAtSign(), "REQUIREMENT_WEAKENED");
    }

    static void text(String value, int limit) { require(value != null && !value.isBlank() && value.length() <= limit, "INVALID_TEXT"); }
    static void require(boolean condition, String code) { if (!condition) throw new TemplateValidationException(code); }
}
