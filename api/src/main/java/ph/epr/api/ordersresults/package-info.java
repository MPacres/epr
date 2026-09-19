/**
 * Orders and results module: requisitions, report matching, review, corrections, and ownership.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Orders and Results",
    allowedDependencies = {"encountertemplates", "platformoperations", "identitytenancy", "documentscommunication", "carecoordination"}
)
package ph.epr.api.ordersresults;
