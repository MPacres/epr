/**
 * Patient registry module: patient identities, identifiers, duplicate review, and identity links.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Patient Registry",
    allowedDependencies = {"platformoperations", "identitytenancy"}
)
package ph.epr.api.patientregistry;
