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
            assertThat(validator.validate(definition, List.of(), AnswerValidator.Stage.SIGN)).hasSize(6);
        }
    }
    @Test void oldSyntheticReleasesRemainValidAndNewSectionsRequireExplicitAnswers() throws Exception {
        for (var key : List.of("general.outpatient", "obgyn.gynecology.initial", "obgyn.gynecology.followup",
            "obgyn.prenatal.initial", "obgyn.prenatal.followup", "obgyn.postpartum.followup")) {
            TemplateDefinition old;
            try (var stream = getClass().getResourceAsStream("/encounter-templates/synthetic/v1/" + key + ".json")) {
                old = ClinicalJson.read(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), TemplateDefinition.class);
            }
            assertThat(old.businessVersion()).isEqualTo("1.0.0");
            assertThat(validator.validate(old, List.of(), AnswerValidator.Stage.SIGN)).hasSize(4);
            var narrative = fixture("v1.1/" + key);
            assertThat(narrative.businessVersion()).isEqualTo("1.1.0");
            assertThat(compiler.validate(narrative)).isNotEmpty();
            assertThat(ClinicalJson.hash(ClinicalJson.read(ClinicalJson.write(narrative), TemplateDefinition.class)))
                .isEqualTo(ClinicalJson.hash(narrative));
            var next = fixture(key);
            for (var previous : compiler.validate(old).values())
                compiler.requireCompatibleMeaning(previous, compiler.validate(next).get(previous.id()));
            for (var previous : compiler.validate(narrative).values())
                compiler.requireCompatibleMeaning(previous, compiler.validate(next).get(previous.id()));
            var ros = compiler.validate(next).values().stream().filter(f -> f.concept().equals("visit.ros")).findFirst().orElseThrow();
            var exam = compiler.validate(next).values().stream().filter(f -> f.concept().equals("visit.physical-examination")).findFirst().orElseThrow();
            var declined = new Answer(exam.id(), null, null, MissingState.DECLINED, source(), Answer.Review.REVIEWED, null, null);
            assertThat(validator.validate(next, List.of(declined), AnswerValidator.Stage.SIGN)).contains(ros.id() + ":REQUIRED");
            assertThat(validator.validate(next, List.of(new Answer(ros.id(), null, null, MissingState.NOT_ASSESSED, source(), Answer.Review.REVIEWED, null, null), declined), AnswerValidator.Stage.SIGN))
                .doesNotContain(ros.id() + ":REQUIRED", exam.id() + ":REQUIRED");
        }
    }
    @Test void quickFindingsRequireClinicianSelectionFromThePinnedTemplate() throws Exception {
        var definition = fixture("general.outpatient");
        var ros = compiler.validate(definition).values().stream().filter(f -> f.concept().equals("visit.ros")).findFirst().orElseThrow();
        assertThat(ros.quickFindings()).isNotEmpty();
        assertThat(validator.validate(definition, List.of(), AnswerValidator.Stage.SIGN)).contains(ros.id() + ":REQUIRED");
        var selected = new Answer.Finding("respiratory", List.of("Denies cough."), "Reports intermittent wheezing.");
        var value = new Answer.Value(null, null, null, List.of(), null, null, List.of(), List.of(selected));
        assertThat(validator.validate(definition, List.of(answer(ros.id(), null, value)), AnswerValidator.Stage.DRAFT)).isEmpty();
        assertThatThrownBy(() -> validator.validate(definition, List.of(answer(ros.id(), null,
            new Answer.Value(null, null, null, List.of(), null, null, List.of(),
                List.of(new Answer.Finding("respiratory", List.of("All systems normal."), null))))), AnswerValidator.Stage.DRAFT))
            .hasMessage("INVALID_FINDING");
        assertThatThrownBy(() -> validator.validate(definition, List.of(answer(ros.id(), null,
            new Answer.Value("Unreviewed narrative", null, null, List.of(), null, null, List.of(), List.of(selected)))), AnswerValidator.Stage.DRAFT))
            .hasMessage("FINDINGS_OR_NARRATIVE");
        assertThatThrownBy(() -> validator.validate(definition, List.of(answer(ros.id(), null,
            new Answer.Value(null, null, null, List.of(), null, null, List.of(),
                List.of(new Answer.Finding("respiratory", List.of(), null))))), AnswerValidator.Stage.DRAFT))
            .hasMessage("EMPTY_FINDING");
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
    @Test void historyGroupContainerDoesNotNeedSeparateReviewButItsItemsDo() throws Exception {
        var definition = fixture("obgyn.prenatal.initial");
        var group = compiler.validate(definition).values().stream().filter(f -> f.type() == Widget.GROUP).findFirst().orElseThrow();
        var item = UUID.randomUUID();
        var parent = new Answer(group.id(), null, new Answer.Value(null, null, null, List.of(), null, null, List.of(item)),
            null, source(), Answer.Review.NOT_REVIEWED, null, null);
        var child = answer(group.fields().getFirst().id(), item, text("Outcome not known"));
        var errors = validator.validate(definition, List.of(parent, child), AnswerValidator.Stage.SIGN);
        assertThat(errors).doesNotContain(group.id() + ":SOURCE_REVIEW", child.fieldId() + "/" + item + ":SOURCE_REVIEW");
        var unreviewedChild = new Answer(child.fieldId(), item, child.value(), null, child.source(), Answer.Review.NOT_REVIEWED, null, null);
        assertThat(validator.validate(definition, List.of(parent, unreviewedChild), AnswerValidator.Stage.SIGN))
            .contains(child.fieldId() + "/" + item + ":SOURCE_REVIEW");
        var unknownGroup = new Answer(group.id(), null, null, MissingState.UNKNOWN, source(), Answer.Review.NOT_REVIEWED, null, null);
        assertThat(validator.validate(definition, List.of(unknownGroup), AnswerValidator.Stage.SIGN))
            .contains(group.id() + ":SOURCE_REVIEW");
    }
    private Answer.Source source() { return new Answer.Source("PATIENT_REPORTED", null, null, null, "Synthetic patient report"); }
    private Answer.Value text(String value) { return new Answer.Value(value, null, null, List.of(), null, null, List.of()); }
    private Answer answer(UUID field, UUID item, Answer.Value value) { return new Answer(field, item, value, null, source(), Answer.Review.REVIEWED, null, null); }
}
