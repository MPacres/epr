package ph.epr.api.platformoperations.application;

import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;

public interface TenantDatabaseManager {

	TenantDatabaseRoute provision(PracticeTenant tenant);
}
