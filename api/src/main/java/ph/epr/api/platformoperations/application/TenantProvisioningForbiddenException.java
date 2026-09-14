package ph.epr.api.platformoperations.application;

public class TenantProvisioningForbiddenException extends RuntimeException {

	public TenantProvisioningForbiddenException() {
		super("Only an enabled platform superadmin can provision Practice tenants.");
	}
}
