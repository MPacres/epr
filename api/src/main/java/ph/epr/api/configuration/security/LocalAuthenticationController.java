package ph.epr.api.configuration.security;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.epr.api.identitytenancy.PlatformUserDirectory;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("local")
class LocalAuthenticationController {

	static final String REMEMBER_ME_ATTRIBUTE = "epr.remember-me";
	private static final int REMEMBERED_SESSION_SECONDS = Math.toIntExact(Duration.ofDays(30).toSeconds());

	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository;
	private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
	private final PlatformUserDirectory users;

	LocalAuthenticationController(
		AuthenticationManager authenticationManager,
		SecurityContextRepository securityContextRepository,
		SessionAuthenticationStrategy sessionAuthenticationStrategy,
		PlatformUserDirectory users
	) {
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
		this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
		this.users = users;
	}

	@PostMapping("/login")
	ResponseEntity<?> login(
		@Valid @RequestBody LoginRequest credentials,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		try {
			var authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(credentials.username(), credentials.password())
			);
			if (credentials.remember()) {
				request.setAttribute(REMEMBER_ME_ATTRIBUTE, Boolean.TRUE);
				request.getSession(true).setMaxInactiveInterval(REMEMBERED_SESSION_SECONDS);
			}
			sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
			var context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);
			securityContextRepository.saveContext(context, request, response);

			return users.findByUsername(authentication.getName())
				.map(account -> ResponseEntity.ok(AuthSessionController.SessionResponse.from(account)))
				.orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
		}
		catch (AuthenticationException exception) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new LoginError("INVALID_CREDENTIALS", "The username or password is incorrect."));
		}
	}

	record LoginRequest(@NotBlank String username, @NotBlank String password, boolean remember) {
	}

	record LoginError(String code, String message) {
	}
}

