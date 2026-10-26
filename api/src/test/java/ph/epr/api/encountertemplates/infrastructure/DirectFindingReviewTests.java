package ph.epr.api.encountertemplates.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ph.epr.api.encountertemplates.domain.Answer;
import ph.epr.api.encountertemplates.domain.TemplateDefinition;

class DirectFindingReviewTests {
    private final UUID fieldId = UUID.randomUUID();
    private final Answer.Source direct = new Answer.Source("CLINICIAN_ENTERED", null, null, null, "Entered during this encounter");
    private final Answer.Source imported = new Answer.Source("PATIENT_REPORTED", null, null, null, "Prior account requiring review");

    @Test void freshClinicianAnswersAndDeliberateMissingStateAreReviewedAtSave() {
        var finding = answer(direct, new Answer.Value("Reported cough; no fever", null, null, List.of(), null, null, List.of()), null);
        assertThat(JdbcEncounterService.reviewedClinicianEntry(null, finding).review()).isEqualTo(Answer.Review.REVIEWED);
        var declined = answer(direct, null, TemplateDefinition.MissingState.DECLINED);
        assertThat(JdbcEncounterService.reviewedClinicianEntry(finding, declined).review()).isEqualTo(Answer.Review.REVIEWED);
        var plan = answer(direct, new Answer.Value("Follow up in two weeks", null, null, List.of(), null, null, List.of()), null);
        assertThat(JdbcEncounterService.reviewedClinicianEntry(null, plan).review()).isEqualTo(Answer.Review.REVIEWED);
    }

    @Test void importedAndUnchangedAnswersDoNotInheritReview() {
        var importedAnswer = answer(imported, new Answer.Value("Copied text", null, null, List.of(), null, null, List.of()), null);
        assertThat(JdbcEncounterService.reviewedClinicianEntry(null, importedAnswer).review()).isEqualTo(Answer.Review.NOT_REVIEWED);
        var directAnswer = answer(direct, new Answer.Value("Observed finding", null, null, List.of(), null, null, List.of()), null);
        assertThat(JdbcEncounterService.reviewedClinicianEntry(directAnswer, directAnswer).review()).isEqualTo(Answer.Review.NOT_REVIEWED);
        var reclassified = new Answer(directAnswer.fieldId(), directAnswer.groupItemId(), importedAnswer.value(), null, direct,
            Answer.Review.NOT_REVIEWED, null, null);
        assertThat(JdbcEncounterService.reviewedClinicianEntry(importedAnswer, reclassified).review()).isEqualTo(Answer.Review.NOT_REVIEWED);
    }
    private Answer answer(Answer.Source source, Answer.Value value, TemplateDefinition.MissingState missing) {
        return new Answer(fieldId, null, value, missing, source, Answer.Review.NOT_REVIEWED, null, null);
    }
}
