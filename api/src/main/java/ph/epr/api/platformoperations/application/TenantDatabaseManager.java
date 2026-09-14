package ph.epr.api.platformoperations.application;

import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;
import ph.epr.api.platformoperations.domain.InitialTenantSite;

public interface TenantDatabaseManager {

	TenantDatabaseRoute provision(PracticeTenant tenant, InitialTenantSite initialSite);
}
