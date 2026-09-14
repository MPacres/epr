package ph.epr.api.configuration.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import ph.epr.api.identitytenancy.PlatformUserDirectory;

class RequiredPasswordChangeFilter extends OncePerRequestFilter {
	private static final String AUTH_PATH = "/api/v1/auth/";
	private static final byte[] REQUIRED_RESPONSE = """
		{"code":"PASSWORD_CHANGE_REQUIRED","message":"Replace the temporary password before continuing."}
		""".strip().getBytes(StandardCharsets.UTF_8);

	private final PlatformUserDirectory users;

	RequiredPasswordChangeFilter(PlatformUserDirectory users) {
		this.users = users;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (requiresPasswordChange(authentication) && isBlockedApiRequest(request)) {
			response.setStatus(428);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setContentLength(REQUIRED_RESPONSE.length);
			response.getOutputStream().write(REQUIRED_RESPONSE);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean requiresPasswordChange(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
			|| "anonymousUser".equals(authentication.getPrincipal())) {
			return false;
		}
		return users.findByUsername(authentication.getName())
			.map(account -> account.enabled() && account.passwordResetRequired())
			.orElse(false);
	}

	private boolean isBlockedApiRequest(HttpServletRequest request) {
		var path = request.getRequestURI();
		return path.startsWith("/api/") && !path.startsWith(AUTH_PATH);
	}
}
