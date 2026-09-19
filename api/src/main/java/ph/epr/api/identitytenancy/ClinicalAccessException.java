package ph.epr.api.identitytenancy;

/** Privacy-safe failure; never includes database routes, credentials or another patient's details. */
public class ClinicalAccessException extends RuntimeException {
    private final int status;
    public ClinicalAccessException(String code, int status) { super(code); this.status = status; }
    public int status() { return status; }
}
