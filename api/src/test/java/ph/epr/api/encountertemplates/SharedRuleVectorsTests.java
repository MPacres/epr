package ph.epr.api.encountertemplates;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import ph.epr.api.encountertemplates.domain.*;
import ph.epr.api.platformoperations.ClinicalJson;
class SharedRuleVectorsTests {
    record Vector(String name, TemplateDefinition.Rule rule, List<Answer> answers, TemplateDefinition.Truth expected) {}
    @Test void matchesTheSharedBrowserVectors() throws Exception {
        try (var stream = getClass().getResourceAsStream("/encounter-templates/rule-v1-vectors.json")) {
            for (var vector : ClinicalJson.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Vector[].class)) {
                var indexed = new HashMap<AnswerValidator.Address, Answer>();
                vector.answers().forEach(a -> indexed.put(new AnswerValidator.Address(a.fieldId(), a.groupItemId()), a));
                assertThat(new AnswerValidator().evaluate(vector.rule(), indexed, null)).as(vector.name()).isEqualTo(vector.expected());
            }
        }
    }
}
