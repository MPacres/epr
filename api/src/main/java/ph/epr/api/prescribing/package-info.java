/**
 * Prescribing module: prescription creation, issuance, renewal, and cancellation.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Prescribing",
    allowedDependencies = {"encountertemplates", "platformoperations", "identitytenancy", "documentscommunication", "clinicalchart"}
)
package ph.epr.api.prescribing;
