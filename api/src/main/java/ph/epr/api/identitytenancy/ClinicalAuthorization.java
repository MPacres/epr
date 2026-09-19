package ph.epr.api.identitytenancy;

import java.util.UUID;

public interface ClinicalAuthorization {
    AuthorizedClinicalScope authorize(AuthenticatedActor actor, UUID practiceId, UUID siteId, boolean approval);
    void authorizePatient(AuthenticatedActor actor, UUID practiceId, UUID siteId, PatientOperation operation);
    enum PatientOperation { DIRECTORY_READ, DETAIL_READ, REGISTER, DEMOGRAPHIC_EDIT }
    java.util.List<ScopeSummary> available(AuthenticatedActor actor);
    record ScopeSummary(UUID practiceId, UUID siteId, String practiceName, String clinicianName) {}
    record AuthorizedClinicalScope(UUID actorId, UUID practiceId, UUID siteId,
                                   boolean reproductiveAccess, PracticeTenant tenant) {}
}
