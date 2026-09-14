package ph.epr.api.configuration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import ph.epr.api.identitytenancy.PlatformUserDirectory;

@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalPasswordAuthenticationConfiguration {

	@Bean
	UserDetailsService localUserDetailsService(PlatformUserDirectory users) {
		return username -> users.findByUsername(username)
			.map(account -> User.withUsername(account.username())
				.password(account.passwordHash())
				.roles(account.role().name())
				.disabled(!account.enabled())
				.build())
			.orElseThrow(() -> new UsernameNotFoundException("Account not found"));
	}

	@Bean
	AuthenticationManager localAuthenticationManager(
		UserDetailsService userDetailsService,
		PasswordEncoder passwordEncoder
	) {
		var provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}
}

