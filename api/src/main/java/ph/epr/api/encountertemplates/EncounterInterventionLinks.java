package ph.epr.api.encountertemplates;
import java.time.*;
import java.util.*;
import ph.epr.api.identitytenancy.InterventionAuthorization;
import ph.epr.api.platformoperations.TenantTransactions.Context;
/** Server-only association boundary. Source modules own clinical records and publish immutable summaries. */
public interface EncounterInterventionLinks {
    enum Kind { PRESCRIPTION, LAB_ORDER, IMAGING_ORDER, REFERRAL, FOLLOW_UP }
    record EncounterContext(UUID encounterId, UUID patientId, UUID siteId, UUID ownerId,
        String patientName, String recordNumber, String careState, String noteState) {}
    record Summary(Kind kind, UUID sourceId, long sourceVersion, String title, List<String> details,
        String clinicalState, String deliveryState, String fulfillmentState, String reviewState,
        UUID ownerId, LocalDate dueDate, OffsetDateTime occurredAt, UUID artifactId, String artifactHash, UUID groupId) {
        public Summary(Kind kind,UUID sourceId,long sourceVersion,String title,List<String> details,String clinicalState,String deliveryState,String fulfillmentState,String reviewState,UUID ownerId,LocalDate dueDate,OffsetDateTime occurredAt,UUID artifactId,String artifactHash){this(kind,sourceId,sourceVersion,title,details,clinicalState,deliveryState,fulfillmentState,reviewState,ownerId,dueDate,occurredAt,artifactId,artifactHash,null);}
        public Summary { details=details==null?List.of():List.copyOf(details); }
    }
    record Item(UUID linkId, long summaryRevision, Summary summary) {}
    record Page(long interventionSetVersion, List<Item> items, long total, int page, int pageSize) {}
    record Manifest(UUID manifestId, long interventionSetVersion, List<Item> items, String checksum) {}
    /** Only source services that have verified an accepted operational assignment may call these two methods. */
    EncounterContext acceptedWorkContext(Context c, UUID patient, UUID encounter);
    EncounterContext lockAcceptedWork(Context c, UUID patient, UUID encounter);
    EncounterContext context(Context c, UUID patient, UUID encounter);
    EncounterContext lock(Context c, UUID patient, UUID encounter);
    void require(Context c, Kind kind, InterventionAuthorization.Action action);
    List<InterventionAuthorization.Capability> capabilities(Context c, UUID patient, UUID encounter);
    void append(Context c, UUID patient, UUID encounter, Summary summary);
    Page list(Context c, UUID patient, UUID encounter, int page, int size);
    Manifest capture(Context c, UUID patient, UUID encounter, UUID manifestId);
    Manifest manifest(Context c, UUID patient, UUID encounter, UUID manifestId);
    long version(Context c, UUID patient, UUID encounter);
}
