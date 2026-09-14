package ph.epr.api.identitytenancy;

public record TemporaryPasswordIssue(
	String temporaryPassword,
	boolean newlyIssued
) {
}
