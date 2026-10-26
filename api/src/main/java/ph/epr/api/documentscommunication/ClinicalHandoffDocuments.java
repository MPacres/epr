package ph.epr.api.documentscommunication;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.identitytenancy.ClinicalDocumentIdentity;
import ph.epr.api.identitytenancy.PracticeProvisioningRegistry;
import ph.epr.api.platformoperations.TenantTransactions.Context;

/** Print layout for reviewed local synthetic documents; the archived bytes remain the authority. */
@Service
@Profile("local")
public class ClinicalHandoffDocuments {
    private final PracticeProvisioningRegistry practices;
    private final ClinicalDocumentIdentity identities;

    public ClinicalHandoffDocuments(PracticeProvisioningRegistry practices, ClinicalDocumentIdentity identities) {
        this.practices = practices;
        this.identities = identities;
    }

    public record Field(String label, String value) {}
    public record Section(String heading, List<Field> fields) {}
    public record Heading(String practice, String site, String clinician) {}

    public static String clinicalTime(OffsetDateTime value) {
        return value == null ? "" : value.atZoneSameInstant(ZoneId.of("Asia/Manila"))
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a 'PHT'", Locale.ENGLISH));
    }

    public byte[] render(Context c, UUID clinicianId, String title, String patientName, String recordNumber,
                         UUID reference, List<Section> sections) {
        return render(c, clinicianId, title, patientName, recordNumber, reference, List.of(), sections, false);
    }

    public byte[] renderLabOrder(Context c, UUID clinicianId, String patientName, String recordNumber,
                                 UUID reference, List<Field> orderFields, List<Section> tests) {
        return render(c, clinicianId, "Lab Order Form", patientName, recordNumber, reference, orderFields, tests, true);
    }

    private byte[] render(Context c, UUID clinicianId, String title, String patientName, String recordNumber,
                          UUID reference, List<Field> orderFields, List<Section> sections, boolean labOrder) {
        var practice = practices.findByPracticeId(c.practiceId())
            .orElseThrow(() -> new ClinicalAccessException("PRACTICE_UNAVAILABLE", 404)).displayName();
        var site = c.jdbc().sql("SELECT name FROM practice_site WHERE practice_id=:practice AND site_id=:site")
            .param("practice", c.practiceId()).param("site", c.siteId()).query(String.class)
            .optional().orElseThrow(() -> new ClinicalAccessException("SITE_UNAVAILABLE", 404));
        var clinician = identities.clinicianName(c.practiceId(), clinicianId)
            .orElse("Clinician reference " + clinicianId);
        return build(new Heading(practice, site, clinician), title, patientName, recordNumber, reference, orderFields, sections, labOrder);
    }

    static byte[] build(Heading heading, String title, String patientName, String recordNumber,
                        UUID reference, List<Section> sections) {
        return build(heading, title, patientName, recordNumber, reference, List.of(), sections, false);
    }

    static byte[] build(Heading heading, String title, String patientName, String recordNumber,
                        UUID reference, List<Field> orderFields, List<Section> sections, boolean labOrder) {
        var html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>")
            .append(escape(title)).append("</title><style>")
            .append("@page{size:A4;margin:17mm}*{box-sizing:border-box}body{max-width:790px;margin:0 auto;padding:24px;color:#192943;font:15px/1.5 Arial,sans-serif}")
            .append("header{display:flex;justify-content:space-between;gap:20px;padding-bottom:14px;border-bottom:2px solid #245ce5}header div{display:grid}header strong{font-size:18px;color:#10234a}header span,.meta{color:#53627a;font-size:12px}")
            .append("h1{font-size:25px;margin:22px 0 4px;color:#10234a}.warning{margin:16px 0;padding:10px 12px;border:1px solid #986014;background:#fff5e4;color:#65400e;font-size:12px;font-weight:700}")
            .append(".identity{display:grid;grid-template-columns:2fr 1fr;gap:12px;margin:20px 0;padding:14px;border:1px solid #d8e3f0;border-radius:6px}.field{display:grid;gap:3px;min-width:0}.field b{font-size:11px;color:#53627a;text-transform:uppercase;letter-spacing:.04em}.field span{white-space:pre-wrap;overflow-wrap:anywhere}")
            .append("section{margin:22px 0;break-inside:avoid}section h2{font-size:17px;margin:0 0 10px;border-bottom:1px solid #d8e3f0;padding-bottom:6px}.fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px}.fields .wide{grid-column:1/-1}.order-details{padding:16px;border:1px solid #d8e3f0;border-radius:6px}.order-details h2{margin-top:0}.lab-tests{margin-top:24px}.lab-tests>h2{font-size:17px;color:#10234a}.lab-tests section{margin:0;padding:12px 0;border-bottom:1px solid #d8e3f0}.lab-tests section h3{font-size:15px;margin:0 0 5px;color:#10234a}.lab-tests .fields:empty{display:none}")
            .append("footer{margin-top:26px;border-top:1px solid #d8e3f0;padding-top:13px;break-inside:avoid}footer p{margin:4px 0}.reference{font-size:11px;color:#53627a;overflow-wrap:anywhere}")
            .append("@media(max-width:520px){body{padding:18px}.identity,.fields{grid-template-columns:1fr}}@media print{body{max-width:none;padding:0}.warning{-webkit-print-color-adjust:exact;print-color-adjust:exact}}")
            .append("</style></head><body><header><div><strong>").append(escape(heading.practice()))
            .append("</strong><span>").append(escape(heading.site())).append("</span></div><span>Outpatient document</span></header>")
            .append("<div class=\"warning\">Training copy · Not for patient care.</div>")
            .append("<h1>").append(escape(title)).append("</h1><p class=\"meta\">Document for review.</p>")
            .append("<div class=\"identity\">");
        field(html, new Field("Patient", patientName));
        field(html, new Field("Patient record number", recordNumber));
        html.append("</div>");
        if (labOrder) {
            html.append("<section class=\"order-details\"><h2>Order details</h2><div class=\"fields\">");
            for (var orderField : orderFields) if (orderField.value() != null && !orderField.value().isBlank()) field(html, orderField);
            html.append("</div></section><div class=\"lab-tests\"><h2>Requested tests</h2>");
            for (var section : sections) {
                html.append("<section><h3>").append(escape(section.heading())).append("</h3><div class=\"fields\">");
                for (var testField : section.fields()) if (testField.value() != null && !testField.value().isBlank()) field(html, testField);
                html.append("</div></section>");
            }
            html.append("</div>");
        } else {
            for (var section : sections) {
                html.append("<section><h2>").append(escape(section.heading())).append("</h2><div class=\"fields\">");
                for (var sectionField : section.fields()) if (sectionField.value() != null && !sectionField.value().isBlank()) field(html, sectionField);
                html.append("</div></section>");
            }
        }
        html.append("<footer><p><strong>Requesting clinician:</strong> ").append(escape(heading.clinician()))
            .append("</p>");
        if (!labOrder) html.append("<p class=\"meta\">Review was recorded in the training environment. Signer credentials were not independently verified.</p>");
        html
            .append("<p class=\"reference\">Document reference: ").append(reference).append(". Printing does not confirm delivery, performance, dispensing, receipt, or review.</p></footer></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder html, Field field) {
        html.append("<div class=\"field\"><b>").append(escape(field.label())).append("</b><span>")
            .append(escape(field.value())).append("</span></div>");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
