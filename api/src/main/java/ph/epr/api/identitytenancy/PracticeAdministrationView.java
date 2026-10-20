package ph.epr.api.identitytenancy;

public record PracticeAdministrationView(
	PracticeTenant tenant,
	String administratorName,
	String administratorEmail,
	String administratorSetupStatus,
	ClinicalReadiness clinicalReadiness
) {
	public PracticeAdministrationView(PracticeTenant tenant, String administratorName,
		String administratorEmail, String administratorSetupStatus) {
		this(tenant, administratorName, administratorEmail, administratorSetupStatus,
			ClinicalReadiness.evaluate(tenant.schemaVersion(), null, false, java.util.List.of(),
				tenant.status() == PracticeProvisioningStatus.QUARANTINED, tenant.failureMessage()));
	}
}
