/**
 * Care coordination module: clinical inbox, referrals, follow-up, and escalation.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Care Coordination",
    allowedDependencies = {"encountertemplates", "platformoperations", "identitytenancy", "documentscommunication"}
)
package ph.epr.api.carecoordination;
