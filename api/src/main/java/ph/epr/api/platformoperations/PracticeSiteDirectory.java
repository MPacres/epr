package ph.epr.api.platformoperations;
import java.util.*;
import ph.epr.api.identitytenancy.AuthenticatedActor;
/** Administrative site metadata only: never an executable clinical context or patient records. */
public interface PracticeSiteDirectory {
    record TeamSite(UUID siteId, String name) {}
    List<TeamSite> sites(AuthenticatedActor actor, UUID practice);
}
