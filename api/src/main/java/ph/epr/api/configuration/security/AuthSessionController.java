package ph.epr.api.configuration.security;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.epr.api.identitytenancy.PlatformUserDirectory;

@RestController
@RequestMapping("/api/v1/auth")
class AuthSessionController {

	private final PlatformUserDirectory users;

	AuthSessionController(PlatformUserDirectory users) {
		this.users = users;
	}

	@GetMapping("/csrf")
	CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	@GetMapping("/session")
	ResponseEntity<SessionResponse> session(Authentication authentication) {
		return users.findByUsername(authentication.getName())
			.filter(account -> account.enabled())
			.map(account -> ResponseEntity.ok(SessionResponse.from(account)))
			.orElseGet(() -> ResponseEntity.status(401).build());
	}

	record CsrfResponse(String headerName, String token) {
	}

	record SessionResponse(
		String username,
		String displayName,
		List<String> roles,
		boolean passwordResetRequired
	) {
		static SessionResponse from(ph.epr.api.identitytenancy.PlatformUser account) {
			return new SessionResponse(
				account.username(), account.displayName(), List.of(account.role().name()), account.passwordResetRequired()
			);
		}
	}
}
