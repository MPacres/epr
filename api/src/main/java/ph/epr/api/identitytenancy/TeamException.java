package ph.epr.api.identitytenancy;
public class TeamException extends RuntimeException {
    private final int status;
    public TeamException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
