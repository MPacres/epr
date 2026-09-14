package ph.epr.api.platformoperations.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("epr.tenant-provisioning")
public record TenantProvisioningProperties(
	boolean enabled,
	String adminJdbcUrl,
	String adminUsername,
	String adminPassword,
	String tenantJdbcUrlTemplate,
	String localCredentialEncryptionKey
) {
}
