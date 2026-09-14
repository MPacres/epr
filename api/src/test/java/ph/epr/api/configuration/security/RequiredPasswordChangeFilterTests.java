package ph.epr.api.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;

@ExtendWith(MockitoExtension.class)
class RequiredPasswordChangeFilterTests {
	@Mock PlatformUserDirectory users;
	@Mock FilterChain chain;

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void blocksOrdinaryApiAccessUntilTheTemporaryPasswordIsReplaced() throws Exception {
		var account = new PlatformUser(
			UUID.randomUUID(), "admin@example.test", "Practice Admin", "hash",
			PlatformRole.PRACTICE_STAFF, true, true
		);
		when(users.findByUsername(account.username())).thenReturn(Optional.of(account));
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
			account.username(), null, List.of(new SimpleGrantedAuthority("ROLE_PRACTICE_STAFF"))
		));
		var request = new MockHttpServletRequest("GET", "/api/v1/admin/practices");
		request.setRequestURI("/api/v1/admin/practices");
		var response = new MockHttpServletResponse();

		new RequiredPasswordChangeFilter(users).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(428);
		assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
	}

	@Test
	void permitsTheAuthenticationEndpointsNeededToCompleteTheChange() throws Exception {
		var account = new PlatformUser(
			UUID.randomUUID(), "admin@example.test", "Practice Admin", "hash",
			PlatformRole.PRACTICE_STAFF, true, true
		);
		when(users.findByUsername(account.username())).thenReturn(Optional.of(account));
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
			account.username(), null, List.of(new SimpleGrantedAuthority("ROLE_PRACTICE_STAFF"))
		));
		var request = new MockHttpServletRequest("POST", "/api/v1/auth/password-change");
		request.setRequestURI("/api/v1/auth/password-change");
		var response = new MockHttpServletResponse();

		new RequiredPasswordChangeFilter(users).doFilter(request, response, chain);

		org.mockito.Mockito.verify(chain).doFilter(request, response);
	}
}
