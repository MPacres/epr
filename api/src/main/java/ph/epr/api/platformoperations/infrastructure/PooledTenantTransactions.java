package ph.epr.api.platformoperations.infrastructure;

import java.util.*;
import java.util.function.Function;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.TenantTransactions;

/** At most four tenant pools, two connections each; busy pools are never evicted. */
@Component
@Profile("local")
class PooledTenantTransactions implements TenantTransactions, ph.epr.api.platformoperations.PracticeSiteDirectory {
    private static final int MAX_POOLS = 4;
    private final ClinicalAuthorization authorization;
    private final TenantCredentialStore credentials;
    private final PracticeTeam team;
    private final PracticeProvisioningRegistry registry;
    private final Map<UUID, Pool> pools = new LinkedHashMap<>(8, .75f, true);

    PooledTenantTransactions(ClinicalAuthorization authorization, TenantCredentialStore credentials, PracticeTeam team, PracticeProvisioningRegistry registry) {
        this.authorization = authorization; this.credentials = credentials; this.team = team; this.registry = registry;
    }

    @Override
    public <T> T execute(AuthenticatedActor actor, UUID practiceId, UUID siteId, boolean approval, Function<Context, T> work) {
        var scope = authorization.authorize(actor, practiceId, siteId, approval);
        if (!Set.of("11").contains(scope.tenant().schemaVersion())) throw new ClinicalAccessException("TENANT_SCHEMA_UNAVAILABLE", 503);
        var pool = acquire(scope.tenant());
        try {
            var transaction = new TransactionTemplate(new JdbcTransactionManager(pool.source));
            transaction.setTimeout(30);
            return transaction.execute(status -> {
                var jdbc = JdbcClient.create(pool.source);
                var identity = jdbc.sql("SELECT practice_id, current_database() AS database_name FROM tenant_practice")
                    .query((rs, n) -> new Identity(rs.getObject(1, UUID.class), rs.getString(2))).list();
                if (identity.size() != 1 || !identity.getFirst().practice().equals(practiceId)
                    || !identity.getFirst().database().equals(scope.tenant().databaseName()))
                    throw new ClinicalAccessException("TENANT_IDENTITY_MISMATCH", 503);
                var validSite = jdbc.sql("SELECT EXISTS(SELECT 1 FROM practice_site WHERE practice_id = :practice AND site_id = :site)")
                    .param("practice", practiceId).param("site", siteId).query(Boolean.class).single();
                if (!validSite) throw new ClinicalAccessException("SITE_UNAVAILABLE", 404);
                return work.apply(new Context(jdbc, practiceId, siteId, scope.actorId(), scope.reproductiveAccess()));
            });
        } finally { release(pool); }
    }

    @Override
    public List<TeamSite> sites(AuthenticatedActor actor, UUID practice) {
        team.authorize(actor, practice);
        var tenant = registry.findByPracticeId(practice).filter(PracticeTenant::isRoutable)
            .orElseThrow(() -> new TeamException(503, "Practice sites are unavailable. Try again later."));
        var pool = acquire(tenant);
        try {
            var transaction = new TransactionTemplate(new JdbcTransactionManager(pool.source));
            transaction.setReadOnly(true); transaction.setTimeout(15);
            return transaction.execute(status -> {
                var jdbc = JdbcClient.create(pool.source);
                var identities = jdbc.sql("SELECT practice_id,current_database() FROM tenant_practice")
                    .query((r,n)->new Identity(r.getObject(1,UUID.class),r.getString(2))).list();
                if (identities.size()!=1 || !identities.getFirst().practice().equals(practice)
                    || !identities.getFirst().database().equals(tenant.databaseName()))
                    throw new TeamException(503,"Practice site identity could not be verified.");
                return jdbc.sql("SELECT site_id,name FROM practice_site WHERE practice_id=:p ORDER BY name,site_id")
                    .param("p",practice).query((r,n)->new TeamSite(r.getObject(1,UUID.class),r.getString(2))).list();
            });
        } finally { release(pool); }
    }

    private synchronized Pool acquire(PracticeTenant tenant) {
        var pool = pools.get(tenant.practiceId());
        if (pool != null && !pool.matches(tenant)) {
            if (pool.leases > 0) throw new ClinicalAccessException("TENANT_ROUTE_CHANGING", 503);
            pool.source.close(); pools.remove(tenant.practiceId()); pool = null;
        }
        // Opportunistic idle eviction, independent of which Practice is requested next.
        var iterator = pools.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() != pool && entry.getValue().leases == 0
                && System.nanoTime() - entry.getValue().lastUsed > 300_000_000_000L) {
                entry.getValue().source.close(); iterator.remove();
            }
        }
        if (pool == null) {
            if (pools.size() >= MAX_POOLS) {
                var idle = pools.entrySet().stream().filter(e -> e.getValue().leases == 0).findFirst()
                    .orElseThrow(() -> new ClinicalAccessException("TENANT_CAPACITY_UNAVAILABLE", 503));
                idle.getValue().source.close(); pools.remove(idle.getKey());
            }
            var credential = credentials.resolveRuntime(tenant.practiceId(), tenant.runtimeSecretReference());
            var config = new HikariConfig();
            config.setJdbcUrl(tenant.jdbcUrl()); config.setUsername(credential.username()); config.setPassword(credential.password());
            config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(5000);
            config.setIdleTimeout(60000); config.setInitializationFailTimeout(-1);
            config.setPoolName("clinical-" + tenant.practiceId());
            config.addDataSourceProperty("options", "-c statement_timeout=25000 -c lock_timeout=5000");
            pool = new Pool(new HikariDataSource(config), tenant.jdbcUrl(), tenant.runtimeSecretReference());
            pools.put(tenant.practiceId(), pool);
        }
        pool.leases++;
        return pool;
    }

    private synchronized void release(Pool pool) { pool.leases--; pool.lastUsed = System.nanoTime(); }
    @PreDestroy public synchronized void close() { pools.values().forEach(pool -> pool.source.close()); pools.clear(); }
    private record Identity(UUID practice, String database) {}
    private static final class Pool {
        final HikariDataSource source;
        final String url;
        final String reference;
        int leases;
        long lastUsed = System.nanoTime();
        Pool(HikariDataSource source, String url, String reference) { this.source = source; this.url = url; this.reference = reference; }
        boolean matches(PracticeTenant tenant) { return url.equals(tenant.jdbcUrl()) && reference.equals(tenant.runtimeSecretReference()); }
    }
}
