package ph.epr.api.patientregistry;

import java.time.*;
import java.util.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;

/** Registry-owned registration and identity APIs. Lists deliberately omit optional personal data. */
public interface PatientRegistrationRecords {
    PatientDetail detail(Context c, UUID patient);
    default PatientDirectory directory(Context c, String search, String identity, String sex, String sort, int page, int size, boolean recent) {
        return directory(c,search,identity,sex,sort,page,size,recent,"all");
    }
    PatientDirectory directory(Context c, String search, String identity, String sex, String sort, int page, int size, boolean recent, String visit);
    PatientDetail create(Context c, UUID key, PatientRegistration command);
    PatientDetail edit(Context c, UUID patient, UUID key, PatientEdit command);
    DuplicateReview check(Context c, DuplicateCheck command);
    void open(Context c, UUID patient, UUID key, UUID reviewId);
    record PatientData(String firstName,
        String middleName,
        String lastName,
        String suffix,
        String birthAccuracy,
        String sex,
        String phone,
        String email,
        String language,
        String communication,
        String street,
        String barangay,
        String city,
        String province,
        String postalCode,
        String philhealthRole,
        String contributor,
        String informationSource,
        String principalName,
        String principalRelationship,
        String representativeName,
        String representativeRelationship,
        String representativePhone,
        String representativeAuthority,
        String emergencyName,
        String emergencyRelationship,
        String emergencyPhone,
        String otherIdType,
        String otherIdIssuer,
        LocalDate birthDate, boolean provisional, boolean pinUnavailable) {}
    record IdentifierOperation(String operation, String value) {}
    record IdentifierChanges(IdentifierOperation pin, IdentifierOperation principalPin, IdentifierOperation otherIdValue) {}
    record IdentifierMetadata(String pin, String principalPin, String otherIdValue, String verification) {}
    record PatientDetail(ClinicalPatientRegistry.Patient patient, PatientData data, IdentifierMetadata identifiers,
                         String originalDisplayName, boolean canEdit) {}
    record PatientRegistration(UUID patientId, PatientData data, IdentifierChanges identifiers, UUID duplicateReviewId, boolean syntheticOnly) {}
    record PatientEdit(long expectedVersion, PatientData data, IdentifierChanges identifiers, UUID duplicateReviewId, String correctionReason) {}
    record DuplicateCheck(PatientData data, UUID excludePatientId) {}
    record DuplicateCandidate(ClinicalPatientRegistry.Patient patient, String reason) {}
    record DuplicateReview(UUID reviewId, List<DuplicateCandidate> candidates, OffsetDateTime expiresAt) {}
    record DirectoryItem(ClinicalPatientRegistry.Patient patient, String birthAccuracy, String sex, String city, OffsetDateTime lastVisit) {}
    record PatientDirectory(List<DirectoryItem> items, long total, int page, int pageSize, String visitHistoryState, OffsetDateTime refreshedAt) {}
}
