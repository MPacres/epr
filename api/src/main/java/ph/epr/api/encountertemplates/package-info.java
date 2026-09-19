/**
 * Encounters and templates module: lifecycle, template releases, revisions, signing, and amendments.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Encounters and Templates",
    allowedDependencies = {"documentscommunication", "clinicalchart", "patientregistry", "platformoperations", "identitytenancy"}
)
package ph.epr.api.encountertemplates;
