package ph.epr.api.platformoperations;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import ph.epr.api.identitytenancy.AuthenticatedActor;

/** Explicit tenant unit of work; synchronous module APIs receive this same transaction context. */
public interface TenantTransactions {
    <T> T execute(AuthenticatedActor actor, UUID practiceId, UUID siteId, boolean approval, Function<Context, T> work);
    record Context(JdbcClient jdbc, UUID practiceId, UUID siteId, UUID actorId, boolean reproductiveAccess) {}
}
