package ph.epr.api.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;

@ExtendWith(MockitoExtension.class)
class LocalAuthenticationControllerTests {

	@Mock AuthenticationManager authenticationManager;
	@Mock SecurityContextRepository securityContextRepository;
	@Mock SessionAuthenticationStrategy sessionAuthenticationStrategy;
	@Mock PlatformUserDirectory users;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void createsRememberedSessionOnlyAfterSuccessfulAuthentication() {
		var account = new PlatformUser(UUID.randomUUID(), "superadmin", "Local Superadmin", "hash", PlatformRole.SUPERADMIN, true);
		var authentication = UsernamePasswordAuthenticationToken.authenticated(
			"superadmin",
			null,
			List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN"))
		);
		when(authenticationManager.authenticate(any())).thenReturn(authentication);
		when(users.findByUsername("superadmin")).thenReturn(Optional.of(account));
		var controller = controller();
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();

		var result = controller.login(
			new LocalAuthenticationController.LoginRequest("superadmin", "correct", true),
			request,
			response
		);

		assertThat(result.getStatusCode().value()).isEqualTo(200);
		assertThat(request.getAttribute(LocalAuthenticationController.REMEMBER_ME_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
		assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(30 * 24 * 60 * 60);
		verify(sessionAuthenticationStrategy).onAuthentication(authentication, request, response);
		verify(securityContextRepository).saveContext(any(), any(), any());
	}

	@Test
	void rejectsInvalidCredentialsWithoutCreatingAnAuthenticatedSession() {
		when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad credentials"));
		var controller = controller();
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();

		var result = controller.login(
			new LocalAuthenticationController.LoginRequest("superadmin", "wrong", false),
			request,
			response
		);

		assertThat(result.getStatusCode().value()).isEqualTo(401);
		assertThat(request.getSession(false)).isNull();
		verify(sessionAuthenticationStrategy, never()).onAuthentication(any(), any(), any());
		verify(securityContextRepository, never()).saveContext(any(), any(), any());
	}

	private LocalAuthenticationController controller() {
		return new LocalAuthenticationController(authenticationManager, securityContextRepository, sessionAuthenticationStrategy, users);
	}
}
