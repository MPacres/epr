/**
 * Scheduling and queue module: appointments, practice sessions, queue entries, and transitions.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Scheduling and Queue",
    allowedDependencies = {"identitytenancy", "platformoperations", "patientregistry", "encountertemplates"}
)
package ph.epr.api.schedulingqueue;
