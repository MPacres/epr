package ph.epr.api.carecoordination;
import java.time.*;
import java.util.*;
import ph.epr.api.platformoperations.TenantTransactions.Context;
/** Synchronous source-to-continuity handoff; notification delivery is never the ownership authority. */
public interface OwnedClinicalWork {
 record Obligation(UUID sourceId,String kind,UUID patientId,UUID encounterId,UUID siteId,UUID ownerId,long sourceVersion,String instruction,LocalDate dueDate) {}
 /** Source services must call this before requesting the minimal accepted-work encounter context. */
 void requireAcceptedAssignment(Context c,String kind,UUID sourceId,UUID patient,UUID encounter);
 void register(Context c,Obligation obligation);
 void reviewed(Context c,String kind,UUID sourceId,long sourceVersion,String evidence);
}
