package ph.epr.api.identitytenancy;
import java.util.*;
/** Transport-neutral policy shared by source records, summaries and documents. */
public interface InterventionAuthorization {
    enum Action { READ, PREPARE, AUTHORIZE, CONTINUE_EXISTING }
    record Capability(String kind, boolean read, boolean prepare, boolean authorize, boolean manageExisting, String unavailableReason) {}
    record OwnerChoice(UUID userId,String displayName) {}
    List<OwnerChoice> eligibleOwners(UUID actor,UUID practice,UUID site,String kind);
    List<Capability> capabilities(UUID actor, UUID practice, UUID site);
    default void require(UUID actor, UUID practice, UUID site, String kind, Action action) {
        var allowed = capabilities(actor, practice, site).stream().filter(c -> c.kind().equals(kind)).anyMatch(c ->
            c.read() && switch(action) { case READ -> true; case PREPARE -> c.prepare(); case AUTHORIZE -> c.authorize(); case CONTINUE_EXISTING -> c.manageExisting(); });
        if (!allowed) throw new ClinicalAccessException("INTERVENTION_ACCESS_DENIED",403);
    }
}
