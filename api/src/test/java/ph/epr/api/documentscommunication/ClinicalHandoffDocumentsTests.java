package ph.epr.api.documentscommunication;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ph.epr.api.documentscommunication.ClinicalHandoffDocuments.Field;
import ph.epr.api.documentscommunication.ClinicalHandoffDocuments.Heading;
import ph.epr.api.documentscommunication.ClinicalHandoffDocuments.Section;

class ClinicalHandoffDocumentsTests {
    @Test void patientHandoffIsLabeledPrintableAndEscapesClinicalContent() {
        var bytes = ClinicalHandoffDocuments.build(new Heading("Demo Practice", "Main Clinic", "Dr. Reyes"),
            "Laboratory requisition", "Patient <A>", "SYN-12", UUID.fromString("11111111-1111-4111-8111-111111111111"),
            List.of(new Section("Requested service", List.of(new Field("Test or examination", "CBC & platelets"),
                new Field("Clinical question", "<script>alert(1)</script>"), new Field("Optional", null)))));
        var html = new String(bytes, StandardCharsets.UTF_8);
        assertThat(html).contains("@page{size:A4", "Laboratory requisition", "Patient &lt;A&gt;", "SYN-12",
            "CBC &amp; platelets", "&lt;script&gt;alert(1)&lt;/script&gt;", "Requesting clinician:", "Dr. Reyes",
            "Document for review.", "Training copy · Not for patient care.",
            "11111111-1111-4111-8111-111111111111");
        assertThat(html).doesNotContain("<script>", "<b>Optional</b>");
    }

    @Test void labOrderPlacesSharedDetailsOnceAboveTestsWithTrainingNotice() {
        var reference = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var html = new String(ClinicalHandoffDocuments.build(new Heading("Example Practice", "Main Clinic", "Dr. Reyes"),
            "Lab Order Form", "Patient A", "SYN-12", reference,
            List.of(new Field("Receiving facility", "External Lab"), new Field("Requested date", "2026-10-26"),
                new Field("Order recorded", "26 Oct 2026, 5:42 PM PHT"), new Field("Clinical question / indication", "TESTS"),
                new Field("Priority", "ROUTINE"), new Field("Request reference", "batch-123")),
            List.of(new Section("CBC", List.of(new Field("Specimen", "Blood"))),
                new Section("TSH", List.of())), true), StandardCharsets.UTF_8);
        assertThat(html).contains("<title>Lab Order Form</title>", "<h2>Order details</h2>", "<h2>Requested tests</h2>", "<h3>CBC</h3>", "<h3>TSH</h3>");
        for (var label : List.of("Receiving facility", "Requested date", "Order recorded", "Clinical question / indication", "Priority", "Request reference"))
            assertThat(html).containsOnlyOnce("<b>" + label + "</b>");
        assertThat(html).contains("Training copy · Not for patient care.");
        assertThat(html).doesNotContain("SYNTHETIC DEMO", "Engineering validation");
    }
}
