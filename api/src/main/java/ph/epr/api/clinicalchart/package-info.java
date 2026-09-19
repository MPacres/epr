/**
 * Clinical chart module: problems, allergies, observations, and medication reconciliation.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Clinical Chart",
    allowedDependencies = {"patientregistry", "platformoperations", "identitytenancy"}
)
package ph.epr.api.clinicalchart;
