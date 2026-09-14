package ph.epr.api.platformoperations.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;
import ph.epr.api.platformoperations.application.TenantDatabaseManager;
import ph.epr.api.platformoperations.application.TenantProvisioningException;
import ph.epr.api.platformoperations.domain.InitialTenantSite;

@Component
@ConditionalOnProperty(
	name = "epr.tenant-provisioning.enabled",
	havingValue = "false",
	matchIfMissing = true
)
class DisabledTenantDatabaseManager implements TenantDatabaseManager {

	@Override
	public TenantDatabaseRoute provision(PracticeTenant tenant, InitialTenantSite initialSite) {
		throw new TenantProvisioningException(
			"TENANT_PROVISIONING_DISABLED",
			"Tenant database provisioning is not enabled on this server."
		);
	}
}
