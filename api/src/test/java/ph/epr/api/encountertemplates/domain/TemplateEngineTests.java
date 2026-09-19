package ph.epr.api.encountertemplates.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import ph.epr.api.platformoperations.ClinicalJson;
import static ph.epr.api.encountertemplates.domain.TemplateDefinition.*;

class TemplateEngineTests {
    private final TemplateCompiler compiler = new TemplateCompiler();
    private final AnswerValidator validator = new AnswerValidator();
    TemplateDefinition fixture(String key) throws Exception {
        try (var stream = getClass().getResourceAsStream("/encounter-templates/synthetic/" + key + ".json")) {
            return ClinicalJson.read(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), TemplateDefinition.class);
        }
    }
    @Test void allSixSyntheticTemplatesCompileAndRoundTripWithStableChecksums() throws Exception {
        for (var key : List.of("general.outpatient", "obgyn.gynecology.initial", "obgyn.gynecology.followup", "obgyn.prenatal.initial", "obgyn.prenatal.followup", "obgyn.postpartum.followup")) {
            var definition = fixture(key);
            assertThat(compiler.validate(definition)).isNotEmpty();
            assertThat(ClinicalJson.hash(ClinicalJson.read(ClinicalJson.write(definition), TemplateDefinition.class))).isEqualTo(ClinicalJson.hash(definition));
            assertThat(validator.validate(definition, List.of(), AnswerValidator.Stage.DRAFT)).isEmpty();
            assertThat(validator.validate(definition, List.of(), AnswerValidator.Stage.SIGN)).hasSize(4);
        }
    }
    @Test void duplicateFieldsAndUnresolvedModulesAreRejected() throws Exception {
        var definition = fixture("obgyn.prenatal.initial");
        var module = new TemplateCompiler.ModuleVersion(UUID.randomUUID(), List.of(), definition.sections());
        var duplicate = new TemplateCompiler.ModuleVersion(UUID.randomUUID(), List.of(module.id()), definition.sections());
        assertThatThrownBy(() -> compiler.compose(definition, List.of(module, duplicate))).hasMessage("DUPLICATE_ID");
        assertThatThrownBy(() -> compiler.compose(definition, List.of(duplicate))).hasMessage("UNRESOLVED_MODULE");
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        assertThatThrownBy(() -> compiler.compose(definition, List.of(new TemplateCompiler.ModuleVersion(a, List.of(b), List.of()),
            new TemplateCompiler.ModuleVersion(b, List.of(a), List.of())))).hasMessage("CYCLIC_MODULES");
    }
    @Test void missingStatesAreUnknownInRulesAndNeverBecomeClinicalNegatives() throws Exception {
        var definition = fixture("obgyn.prenatal.initial");
        var fields = compiler.validate(definition);
        var concern = fields.values().stream().filter(f -> f.key().equals("pregnancy.concerns")).findFirst().orElseThrow();
        var rule = new Rule(Operator.EQ, concern.id(), List.of("PRESENT"), List.of());
        assertThat(validator.evaluate(rule, Map.of(), null)).isEqualTo(Truth.UNKNOWN);
        var missing = new Answer(concern.id(), null, null, MissingState.NOT_ASSESSED, source(), Answer.Review.NOT_REVIEWED, null, null);
        assertThat(validator.evaluate(new Rule(Operator.NOT, null, List.of(), List.of(rule)), Map.of(new AnswerValidator.Address(concern.id(), null), missing), null)).isEqualTo(Truth.UNKNOWN);
        var answered = answer(concern.id(), null, new Answer.Value(null, null, null, List.of("NONE_REPORTED"), null, null, List.of()));
        assertThat(validator.evaluate(rule, Map.of(new AnswerValidator.Address(concern.id(), null), answered), null)).isEqualTo(Truth.FALSE);
    }
    @Test void hiddenAnswersRemainAndBlockSigningUntilExplicitlyResolved() throws Exception {
        var definition = fixture("obgyn.prenatal.initial");
        var field = compiler.validate(definition).values().stream().filter(f -> f.key().equals("pregnancy.concernDetails")).findFirst().orElseThrow();
        var retained = answer(field.id(), null, text("Account entered before collapsing the branch"));
        assertThat(validator.validate(definition, List.of(retained), AnswerValidator.Stage.DRAFT)).isEmpty();
        assertThat(validator.validate(definition, List.of(retained), AnswerValidator.Stage.SIGN)).contains(field.id() + ":HIDDEN_ANSWER_REVIEW");
        var reviewed = new Answer(field.id(), null, retained.value(), null, source(), Answer.Review.REVIEWED, Answer.HiddenResolution.RETAIN_RELEVANT, "Still relevant history");
        assertThat(validator.validate(definition, List.of(reviewed), AnswerValidator.Stage.SIGN)).doesNotContain(field.id() + ":HIDDEN_ANSWER_REVIEW");
    }
    @Test void quantitiesKeepUnitsAndRepeatedItemsCannotBeReassignedOrOrphaned() throws Exception {
        var definition = fixture("obgyn.prenatal.initial");
        var fields = compiler.validate(definition);
        var weight = fields.values().stream().filter(f -> f.type() == Widget.QUANTITY).findFirst().orElseThrow();
        var invalid = answer(weight.id(), null, new Answer.Value(null, new BigDecimal("65.2"), "lb", List.of(), null, null, List.of()));
        assertThatThrownBy(() -> validator.validate(definition, List.of(invalid), AnswerValidator.Stage.DRAFT)).hasMessage("INVALID_UNIT");
        var group = fields.values().stream().filter(f -> f.type() == Widget.GROUP).findFirst().orElseThrow();
        var item = UUID.randomUUID();
        var child = answer(group.fields().getFirst().id(), item, text("Outcome not known"));
        assertThatThrownBy(() -> validator.validate(definition, List.of(child), AnswerValidator.Stage.DRAFT)).hasMessage("ORPHAN_GROUP_ITEM");
        var parent = answer(group.id(), null, new Answer.Value(null, null, null, List.of(), null, null, List.of(item)));
        assertThat(validator.validate(definition, List.of(child, parent), AnswerValidator.Stage.DRAFT)).isEmpty();
    }
    private Answer.Source source() { return new Answer.Source("PATIENT_REPORTED", null, null, null, "Synthetic patient report"); }
    private Answer.Value text(String value) { return new Answer.Value(value, null, null, List.of(), null, null, List.of()); }
    private Answer answer(UUID field, UUID item, Answer.Value value) { return new Answer(field, item, value, null, source(), Answer.Review.REVIEWED, null, null); }
}
