package ph.epr.api.platformoperations.configuration;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantProvisioningProperties.class)
public class TenantProvisioningConfiguration {

	@Bean
	Clock tenantProvisioningClock() {
		return Clock.systemUTC();
	}
}
