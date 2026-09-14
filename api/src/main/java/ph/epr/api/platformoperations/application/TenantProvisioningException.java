package ph.epr.api.platformoperations.application;

public class TenantProvisioningException extends RuntimeException {

	private final String code;

	public TenantProvisioningException(String code, String message) {
		super(message);
		this.code = code;
	}

	public TenantProvisioningException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
