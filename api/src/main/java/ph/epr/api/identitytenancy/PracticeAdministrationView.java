package ph.epr.api.identitytenancy;

public record PracticeAdministrationView(
	PracticeTenant tenant,
	String administratorName,
	String administratorEmail,
	String administratorSetupStatus
) {
}
