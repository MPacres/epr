package ph.epr.api.identitytenancy;
import java.util.*;
public interface ClinicalDeliveryScopes {
 record DeliveryScope(String username,UUID practiceId,UUID siteId) {}
 List<DeliveryScope> enabled();
}
