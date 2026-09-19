package ph.epr.api.patientregistry.infrastructure;

import java.time.*;
import java.util.*;
import java.sql.*;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.patientregistry.*;
import ph.epr.api.platformoperations.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

@Service
class JdbcPatientRegistrationRecords implements PatientRegistrationRecords {
    private final ClinicalPatientRegistry patients;
    private final TenantCommandJournal journal;
    private final LocalPatientProtection protection;
    JdbcPatientRegistrationRecords(ClinicalPatientRegistry patients, TenantCommandJournal journal, LocalPatientProtection protection) {
        this.patients = patients; this.journal = journal; this.protection = protection;
    }
    private static final String SCOPE = """
        FROM clinical_patient p JOIN patient_care_assignment a USING(practice_id, patient_id)
        WHERE p.practice_id = :practice AND a.clinician_id = :actor AND a.enabled
        """;
    public PatientDetail detail(Context c, UUID patient) {
        var result = read(c, patient);
        journal.audit(c, patient, "PATIENT_DETAIL_READ", null);
        return result;
    }
    private PatientDetail read(Context c, UUID patient) {
        var core = patients.requirePatient(c, patient);
        return c.jdbc().sql("SELECT p.* " + SCOPE + " AND p.patient_id = :patient")
            .param("practice", c.practiceId()).param("actor", c.actorId()).param("patient", patient)
            .query((rs, n) -> new PatientDetail(core, data(rs), metadata(secrets(c, patient, rs.getString("protected_identifiers"))), rs.getString("original_display_name"), false)).single();
    }
    public PatientDirectory directory(Context c, String search, String identity, String sex, String sort, int page, int size, boolean recent, String visit) {
        if (search == null || search.length() > 200 || page < 0 || page > 100000 || size < 1 || size > 50
            || !Set.of("all","PROVISIONAL","REPORTED").contains(identity)
            || !Set.of("all","Female","Male","Not recorded").contains(sex)
            || !Set.of("name","newest","last-visit","oldest-visit").contains(sort) || !Set.of("all","visited","never").contains(visit)) fail("INVALID_DIRECTORY_QUERY", 422);
        boolean current = patients.visitsCurrent(c);
        if(!current && (!visit.equals("all") || sort.endsWith("visit"))) fail("VISIT_HISTORY_UNAVAILABLE",409);
        var lastVisit="(SELECT max(v.service_time) FROM patient_visit_projection v WHERE v.practice_id=p.practice_id AND v.site_id=:site AND v.clinician_id=:actor AND v.patient_id=p.patient_id)";
        var conditions = " AND (:identity = 'all' OR p.identity_status = :identity) AND (:sex = 'all' OR p.sex = :sex)"
            + " AND (strpos(lower(p.display_name), lower(:search)) > 0 OR strpos(lower(p.record_number), lower(:search)) > 0)"
            + (visit.equals("all") ? "" : " AND " + lastVisit + (visit.equals("visited") ? " IS NOT NULL" : " IS NULL"))
            + (recent ? " AND EXISTS(SELECT 1 FROM patient_recent_open r WHERE r.practice_id = p.practice_id AND r.patient_id = p.patient_id AND r.actor_id = :actor AND r.opened_at > CURRENT_TIMESTAMP - INTERVAL '30 days')" : "");
        var params = Map.of("practice", c.practiceId(), "actor", c.actorId(), "search", search.strip(), "identity", identity, "sex", sex, "site", c.siteId());
        var total = c.jdbc().sql("SELECT count(*) " + SCOPE + conditions).params(params).query(Long.class).single();
        var order = recent ? "(SELECT r.opened_at FROM patient_recent_open r WHERE r.practice_id=p.practice_id AND r.patient_id=p.patient_id AND r.actor_id=:actor) DESC" : sort.equals("name") ? "lower(p.display_name)" : sort.equals("newest") ? "p.received_at DESC" : lastVisit + (sort.equals("last-visit") ? " DESC NULLS LAST" : " ASC NULLS LAST");
        var items = c.jdbc().sql("SELECT p.*, " + (current ? lastVisit : "CAST(NULL AS timestamptz)") + " AS last_visit " + SCOPE + conditions + " ORDER BY " + order + ", p.patient_id LIMIT :size OFFSET :offset")
            .params(params).param("size", size).param("offset", (long)page * size).query((rs, n) -> new DirectoryItem(core(rs), rs.getString("birth_accuracy"), rs.getString("sex"), rs.getString("city"), rs.getObject("last_visit",OffsetDateTime.class))).list();
        journal.audit(c, c.actorId(), "PATIENT_DIRECTORY_READ", null);
        return new PatientDirectory(items, total, page, size, current ? "CURRENT" : "UNAVAILABLE", OffsetDateTime.now());
    }
    public DuplicateReview check(Context c, DuplicateCheck command) {
        validate(command.data(), command.excludePatientId() == null ? null : read(c, command.excludePatientId()).originalDisplayName());
        lock(c);
        var candidates = candidates(c, command.data(), command.excludePatientId());
        var id = UUID.randomUUID(); var expires = OffsetDateTime.now().plusMinutes(10);
        c.jdbc().sql("""
            INSERT INTO patient_duplicate_review(practice_id, review_id, actor_id, site_id, request_hash, candidate_hash, expires_at, candidate_ids)
            VALUES (:practice, :id, :actor, :site, :request, :candidates, :expires, CAST(:ids AS jsonb))
            """).param("practice", c.practiceId()).param("id", id).param("actor", c.actorId()).param("site", c.siteId())
            .param("request", fingerprint(command.data(), command.excludePatientId())).param("candidates", ClinicalJson.hash(candidates)).param("expires", expires).param("ids",ClinicalJson.write(candidates.stream().map(item->item.patient().patientId()).toList())).update();
        journal.audit(c, id, "PATIENT_DUPLICATES_REVIEWED", null);
        return new DuplicateReview(id, candidates, expires);
    }
    public PatientDetail create(Context c, UUID key, PatientRegistration command) {
        if (!command.syntheticOnly() || command.patientId() == null) fail("INVALID_SYNTHETIC_PATIENT", 422);
        validate(command.data(), null); lock(c);
        // Recheck access before returning an idempotency receipt.
        if (exists(c, command.patientId())) patients.requirePatient(c, command.patientId());
        return journal.execute(c, "patient-registration-v2", key, command, PatientDetail.class, () -> {
            if (exists(c, command.patientId())) fail("PATIENT_ID_CONFLICT", 409);
            review(c, command.data(), null, command.duplicateReviewId());
            var secrets = apply(new Secrets("", "", ""), command.identifiers(), command.data());
            c.jdbc().sql("""
                INSERT INTO clinical_patient(practice_id, patient_id, display_name, birth_date, birth_accuracy, identity_status, created_by)
                VALUES (:practice, :patient, :name, :birth, :accuracy, :status, :actor)
                """).param("practice", c.practiceId()).param("patient", command.patientId()).param("name", name(command.data()))
                .param("birth", command.data().birthDate(), Types.DATE).param("accuracy", command.data().birthAccuracy())
                .param("status", command.data().provisional() ? "PROVISIONAL" : "REPORTED").param("actor", c.actorId()).update();
            c.jdbc().sql("INSERT INTO patient_care_assignment(practice_id, patient_id, clinician_id) VALUES (:practice, :patient, :actor)")
                .param("practice", c.practiceId()).param("patient", command.patientId()).param("actor", c.actorId()).update();
            write(c, command.patientId(), command.data(), secrets, 0);
            revision(c, command.patientId(), 0, "Patient registration", null, command.data(), null, secrets);
            decide(c, command.duplicateReviewId(), command.patientId(), "CREATE_SEPARATE");
            journal.audit(c, command.patientId(), "SYNTHETIC_PATIENT_REGISTERED", null);
            return read(c, command.patientId());
        });
    }
    public PatientDetail edit(Context c, UUID patient, UUID key, PatientEdit command) {
        lock(c); var before = read(c, patient);
        validate(command.data(), before.originalDisplayName());
        return journal.execute(c, "patient-edit/" + patient, key, command, PatientDetail.class, () -> {
            if (before.patient().version() != command.expectedVersion()) fail("PATIENT_VERSION_CONFLICT", 409);
            if (command.correctionReason() == null || command.correctionReason().isBlank() || command.correctionReason().length() > 1000)
                fail("CORRECTION_REASON_REQUIRED", 422);
            review(c, command.data(), patient, command.duplicateReviewId());
            var previousSecrets = loadSecrets(c, patient);
            var nextSecrets = apply(previousSecrets, command.identifiers(), command.data());
            long version = before.patient().version() + 1;
            write(c, patient, command.data(), nextSecrets, version);
            revision(c, patient, version, command.correctionReason(), before.data(), command.data(), previousSecrets, nextSecrets);
            decide(c, command.duplicateReviewId(), patient, "KEEP_SEPARATE");
            journal.audit(c, patient, "PATIENT_DEMOGRAPHICS_CORRECTED", "Correction recorded in protected demographic history");
            return read(c, patient);
        });
    }
    public void open(Context c, UUID patient, UUID key, UUID reviewId) {
        patients.requirePatient(c, patient);
        journal.execute(c, "patient-chart-open/" + patient, key, List.of(patient, Objects.toString(reviewId,"")), Boolean.class, () -> {
            if (reviewId != null) {
                var valid = c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM patient_duplicate_review WHERE practice_id=:practice AND review_id=:review AND actor_id=:actor AND site_id=:site AND expires_at > CURRENT_TIMESTAMP AND decision IS NULL AND candidate_ids @> CAST(:candidate AS jsonb))")
                    .param("practice",c.practiceId()).param("review",reviewId).param("candidate",ClinicalJson.write(List.of(patient))).param("actor",c.actorId()).param("site",c.siteId()).query(Boolean.class).single();
                if (!valid) fail("DUPLICATE_REVIEW_STALE",409);
                decide(c, reviewId, patient, "OPEN_EXISTING");
            }
            c.jdbc().sql("""
                INSERT INTO patient_recent_open(practice_id, actor_id, patient_id) VALUES (:practice, :actor, :patient)
                ON CONFLICT(practice_id, actor_id, patient_id) DO UPDATE SET opened_at = CURRENT_TIMESTAMP
                """).param("practice", c.practiceId()).param("actor", c.actorId()).param("patient", patient).update();
            c.jdbc().sql("""
                DELETE FROM patient_recent_open WHERE practice_id=:practice AND actor_id=:actor AND
                  (opened_at < CURRENT_TIMESTAMP - INTERVAL '30 days' OR patient_id NOT IN
                    (SELECT patient_id FROM patient_recent_open WHERE practice_id=:practice AND actor_id=:actor ORDER BY opened_at DESC LIMIT 50))
                """).param("practice", c.practiceId()).param("actor", c.actorId()).update();
            journal.audit(c, patient, "PATIENT_CHART_OPENED", null); return true;
        });
    }
    private void review(Context c, PatientData data, UUID exclude, UUID review) {
        var current = candidates(c, data, exclude);
        if (current.isEmpty() && review == null) return;
        if (review == null) fail("DUPLICATE_REVIEW_REQUIRED", 409);
        boolean valid = c.jdbc().sql("""
            SELECT EXISTS(SELECT 1 FROM patient_duplicate_review WHERE practice_id=:practice AND review_id=:review
                AND actor_id=:actor AND site_id=:site AND request_hash=:request AND candidate_hash=:candidates
                AND expires_at > CURRENT_TIMESTAMP AND decision IS NULL)
            """).param("practice", c.practiceId()).param("review", review).param("actor", c.actorId()).param("site", c.siteId())
            .param("request", fingerprint(data, exclude)).param("candidates", ClinicalJson.hash(current)).query(Boolean.class).single();
        if (!valid) fail("DUPLICATE_REVIEW_STALE", 409);
    }
    private List<DuplicateCandidate> candidates(Context c, PatientData data, UUID exclude) {
        var result = c.jdbc().sql("SELECT p.* " + SCOPE + """
            AND (CAST(:exclude AS uuid) IS NULL OR p.patient_id <> :exclude)
            AND (lower(regexp_replace(p.display_name, '[[:space:][:punct:]]', '', 'g')) = :name
              OR (:phone <> '' AND regexp_replace(p.phone, '[^0-9]', '', 'g') = :phone)
              OR (CAST(:birth AS date) IS NOT NULL AND p.birth_date=:birth AND :surname <> '' AND strpos(lower(p.display_name),lower(:surname)) > 0))
            ORDER BY p.patient_id LIMIT 21
            """).param("practice", c.practiceId()).param("actor", c.actorId()).param("exclude", exclude, Types.OTHER)
            .param("name", name(data).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]", ""))
            .param("phone", data.phone().replaceAll("[^0-9]", "")).param("birth", data.birthDate(), Types.DATE).param("surname", data.lastName().strip())
            .query((rs,n) -> new DuplicateCandidate(core(rs), "Matching name, phone, or birth date with family name; confirm identity with the patient")).list();
        if (result.size() > 20) fail("DUPLICATE_REVIEW_TOO_BROAD", 409);
        return result;
    }
    private String fingerprint(PatientData d, UUID exclude) {
        return ClinicalJson.hash(Arrays.asList(d.firstName(), d.middleName(), d.lastName(), d.suffix(), d.birthDate(), d.birthAccuracy(), d.phone(), exclude));
    }
    private void decide(Context c, UUID review, UUID patient, String decision) {
        if (review == null) return;
        c.jdbc().sql("UPDATE patient_duplicate_review SET decision=:decision, decided_at=CURRENT_TIMESTAMP, patient_id=:patient WHERE practice_id=:practice AND review_id=:review AND actor_id=:actor")
            .param("practice", c.practiceId()).param("review", review).param("actor", c.actorId()).param("patient", patient).param("decision", decision).update();
    }
    private boolean exists(Context c, UUID patient) {
        return c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM clinical_patient WHERE practice_id=:practice AND patient_id=:patient)")
            .param("practice",c.practiceId()).param("patient",patient).query(Boolean.class).single();
    }
    private void lock(Context c) {
        c.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))").param("scope", c.practiceId()+"/patient-registration").query((rs,n)->true).single();
    }
    private void write(Context c, UUID patient, PatientData d, Secrets secrets, long version) {
        var params = new HashMap<String,Object>();
        params.put("firstName", d.firstName());
        params.put("middleName", d.middleName());
        params.put("lastName", d.lastName());
        params.put("suffix", d.suffix());
        params.put("birthAccuracy", d.birthAccuracy());
        params.put("sex", d.sex());
        params.put("phone", d.phone());
        params.put("email", d.email());
        params.put("language", d.language());
        params.put("communication", d.communication());
        params.put("street", d.street());
        params.put("barangay", d.barangay());
        params.put("city", d.city());
        params.put("province", d.province());
        params.put("postalCode", d.postalCode());
        params.put("philhealthRole", d.philhealthRole());
        params.put("contributor", d.contributor());
        params.put("informationSource", d.informationSource());
        params.put("principalName", d.principalName());
        params.put("principalRelationship", d.principalRelationship());
        params.put("representativeName", d.representativeName());
        params.put("representativeRelationship", d.representativeRelationship());
        params.put("representativePhone", d.representativePhone());
        params.put("representativeAuthority", d.representativeAuthority());
        params.put("emergencyName", d.emergencyName());
        params.put("emergencyRelationship", d.emergencyRelationship());
        params.put("emergencyPhone", d.emergencyPhone());
        params.put("otherIdType", d.otherIdType());
        params.put("otherIdIssuer", d.otherIdIssuer());
        params.put("birth", d.birthDate()); params.put("unavailable", d.pinUnavailable());
        params.put("status", d.provisional() ? "PROVISIONAL" : "REPORTED");
        params.put("name", name(d)); params.put("practice", c.practiceId()); params.put("patient", patient); params.put("version", version);
        params.put("secrets", protection.protect(c.practiceId(), patient, ClinicalJson.write(secrets)));
        c.jdbc().sql("UPDATE clinical_patient SET first_name=:firstName, middle_name=:middleName, last_name=:lastName, suffix=:suffix, birth_accuracy=:birthAccuracy, sex=:sex, phone=:phone, email=:email, language=:language, communication=:communication, street=:street, barangay=:barangay, city=:city, province=:province, postal_code=:postalCode, philhealth_role=:philhealthRole, contributor=:contributor, information_source=:informationSource, principal_name=:principalName, principal_relationship=:principalRelationship, representative_name=:representativeName, representative_relationship=:representativeRelationship, representative_phone=:representativePhone, representative_authority=:representativeAuthority, emergency_name=:emergencyName, emergency_relationship=:emergencyRelationship, emergency_phone=:emergencyPhone, other_id_type=:otherIdType, other_id_issuer=:otherIdIssuer, birth_date=:birth, pin_unavailable=:unavailable, identity_status=:status, display_name=CASE WHEN :name='' THEN original_display_name ELSE :name END, protected_identifiers=:secrets, version=:version WHERE practice_id=:practice AND patient_id=:patient")
            .params(params).update();
    }
    private PatientData data(ResultSet rs) throws SQLException {
        return new PatientData(rs.getString("first_name"), rs.getString("middle_name"), rs.getString("last_name"), rs.getString("suffix"), rs.getString("birth_accuracy"), rs.getString("sex"), rs.getString("phone"), rs.getString("email"), rs.getString("language"), rs.getString("communication"), rs.getString("street"), rs.getString("barangay"), rs.getString("city"), rs.getString("province"), rs.getString("postal_code"), rs.getString("philhealth_role"), rs.getString("contributor"), rs.getString("information_source"), rs.getString("principal_name"), rs.getString("principal_relationship"), rs.getString("representative_name"), rs.getString("representative_relationship"), rs.getString("representative_phone"), rs.getString("representative_authority"), rs.getString("emergency_name"), rs.getString("emergency_relationship"), rs.getString("emergency_phone"), rs.getString("other_id_type"), rs.getString("other_id_issuer"), rs.getObject("birth_date", LocalDate.class), rs.getString("identity_status").equals("PROVISIONAL"), rs.getBoolean("pin_unavailable"));
    }
    private ClinicalPatientRegistry.Patient core(ResultSet rs) throws SQLException {
        return new ClinicalPatientRegistry.Patient(rs.getObject("patient_id", UUID.class),rs.getString("record_number"),rs.getString("display_name"),rs.getObject("birth_date",LocalDate.class),rs.getString("identity_status"),rs.getLong("version"),rs.getString("birth_accuracy"));
    }
    private Secrets loadSecrets(Context c, UUID patient) {
        return c.jdbc().sql("SELECT protected_identifiers FROM clinical_patient WHERE practice_id=:practice AND patient_id=:patient")
            .param("practice",c.practiceId()).param("patient",patient).query((rs,n)->secrets(c,patient,rs.getString(1))).single();
    }
    private Secrets secrets(Context c, UUID patient, String encrypted) {
        return encrypted == null ? new Secrets("","","") : ClinicalJson.read(protection.reveal(c.practiceId(),patient,encrypted),Secrets.class);
    }
    private IdentifierMetadata metadata(Secrets s) { return new IdentifierMetadata(mask(s.pin()),mask(s.principalPin()),mask(s.otherIdValue()),"NOT_VERIFIED"); }
    private String mask(String s) { return s.isBlank() ? "" : "•••• " + s.substring(Math.max(0,s.length()-4)); }
    private Secrets apply(Secrets before, IdentifierChanges changes, PatientData data) {
        if (changes == null) fail("IDENTIFIER_OPERATIONS_REQUIRED",422);
        var s = new Secrets(operation(before.pin(),changes.pin(),true),operation(before.principalPin(),changes.principalPin(),true),operation(before.otherIdValue(),changes.otherIdValue(),false));
        if (data.pinUnavailable() && !s.pin().isEmpty()) fail("PIN_UNAVAILABLE_CONFLICT",422);
        if (!data.philhealthRole().equals("Dependent") && (!s.principalPin().isEmpty() || !data.principalName().isEmpty() || !data.principalRelationship().isEmpty())) fail("PRINCIPAL_REQUIRES_DEPENDENT",422);
        if (!s.otherIdValue().isEmpty() && (data.otherIdType().isBlank() || data.otherIdIssuer().isBlank())) fail("IDENTIFIER_ISSUER_REQUIRED",422);
        return s;
    }
    private String operation(String before, IdentifierOperation op, boolean pin) {
        if (op == null || op.operation() == null) { fail("INVALID_IDENTIFIER_OPERATION",422); return ""; }
        if (op.operation().equals("UNCHANGED") || op.operation().equals("REMOVE")) {
            if (op.value() != null && !op.value().isEmpty()) fail("INVALID_IDENTIFIER_OPERATION",422);
            return op.operation().equals("UNCHANGED") ? before : "";
        }
        if (!op.operation().equals("REPLACE") || op.value()==null || op.value().isBlank() || op.value().length()>200 || op.value().contains("•") || op.value().contains("*")) fail("INVALID_IDENTIFIER_VALUE",422);
        if (pin && !op.value().replaceAll("[\\s-]","").matches("[0-9]{12}")) fail("INVALID_PIN",422);
        return op.value();
    }
    private void revision(Context c, UUID patient, long version, String reason, PatientData before, PatientData after, Secrets oldSecrets, Secrets nextSecrets) {
        var evidence = protection.protect(c.practiceId(),patient,ClinicalJson.write(Arrays.asList(before,after,oldSecrets,nextSecrets)));
        c.jdbc().sql("INSERT INTO patient_demographic_revision(practice_id,patient_id,version,actor_id,reason,protected_evidence) VALUES (:practice,:patient,:version,:actor,:reason,:evidence)")
            .param("practice",c.practiceId()).param("patient",patient).param("version",version).param("actor",c.actorId()).param("reason",reason).param("evidence",evidence).update();
    }
    private String name(PatientData d) { return String.join(" ", List.of(d.firstName(),d.middleName(),d.lastName(),d.suffix()).stream().map(String::strip).filter(s->!s.isEmpty()).toList()); }
    private void validate(PatientData d, String originalName) {
        if (d==null) fail("PATIENT_DATA_REQUIRED",422);
        for (var component : PatientData.class.getRecordComponents()) {
            if (component.getType()!=String.class) continue;
            try { var value=(String)component.getAccessor().invoke(d); if (value==null || value.length()>200 || value.indexOf('\0')>=0) fail("INVALID_FIELD_"+component.getName(),422); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        if ((name(d).isEmpty() && (originalName==null || originalName.isBlank())) || name(d).length()>200) fail("PATIENT_NAME_REQUIRED",422);
        if (!Set.of("exact","estimated","unknown").contains(d.birthAccuracy()) || (d.birthAccuracy().equals("unknown") != (d.birthDate()==null)))
            fail("INVALID_BIRTH_DATE",422);
        if (d.birthDate()!=null && (d.birthDate().isAfter(LocalDate.now(ZoneId.of("Asia/Manila"))) || d.birthDate().getYear()<1)) fail("INVALID_BIRTH_DATE",422);
        oneOf(d.sex(),"Female","Male","Not recorded"); oneOf(d.communication(),"Not recorded","In person","Phone call","SMS","Email");
        oneOf(d.philhealthRole(),"Unknown","Member","Dependent"); oneOf(d.contributor(),"Unknown","Direct contributor","Indirect contributor");
        oneOf(d.informationSource(),"Not recorded","Patient reported","Representative reported","Document presented");
        oneOf(d.representativeAuthority(),"Not recorded","Patient nominated","Parent / guardian","Other — requires review");
        if (!d.email().isBlank() && !d.email().strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) fail("INVALID_EMAIL",422);
        for (var phone : List.of(d.phone(),d.representativePhone(),d.emergencyPhone())) if (!phone.isBlank() && !phone.strip().matches("\\+?[0-9\\s().-]{7,25}")) fail("INVALID_PHONE",422);
    }
    private void oneOf(String value,String... choices) { if (!Arrays.asList(choices).contains(value)) fail("INVALID_PATIENT_OPTION",422); }
    private void fail(String code,int status) { throw new ClinicalAccessException(code,status); }
    record Secrets(String pin,String principalPin,String otherIdValue) {}
}
