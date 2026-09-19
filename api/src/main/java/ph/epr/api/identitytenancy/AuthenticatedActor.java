package ph.epr.api.identitytenancy;

/** Transport adapters supply verified identity only; all authority is resolved for each request. */
public record AuthenticatedActor(String username) {
    public AuthenticatedActor {
        if (username == null || username.isBlank()) throw new ClinicalAccessException("AUTHENTICATION_REQUIRED", 401);
    }
}
