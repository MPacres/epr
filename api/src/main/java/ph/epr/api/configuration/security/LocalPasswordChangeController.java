package ph.epr.api.configuration.security;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeAdministrationConflictException;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("local")
class LocalPasswordChangeController {
	private final PlatformUserDirectory users;
	private final Clock clock;

	LocalPasswordChangeController(PlatformUserDirectory users, Clock clock) {
		this.users = users;
		this.clock = clock;
	}

	@PostMapping("/password-change")
	AuthSessionController.SessionResponse changePassword(
		Authentication authentication,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey,
		@Valid @RequestBody PasswordChangeRequest request
	) {
		if (!request.newPassword().equals(request.confirmPassword())) {
			throw new IllegalArgumentException("The password confirmation does not match.");
		}
		var account = users.findByUsername(authentication.getName())
			.orElseThrow(() -> new PracticeAdministrationConflictException("Account not found."));
		var updated = users.completeRequiredPasswordChange(
			account.id(), request.newPassword(), idempotencyKey, OffsetDateTime.now(clock)
		);
		return AuthSessionController.SessionResponse.from(updated);
	}

	@ExceptionHandler({IllegalArgumentException.class, PracticeAdministrationConflictException.class})
	ProblemDetail invalidPassword(RuntimeException exception) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(URI.create("urn:epr:problem:password-change-invalid"));
		problem.setTitle("Password change could not be completed");
		problem.setProperty("code", "PASSWORD_CHANGE_INVALID");
		return problem;
	}

	record PasswordChangeRequest(
		@NotBlank
		@Size(min = 12, max = 128)
		@Pattern(
			regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).+$",
			message = "must include an uppercase letter, lowercase letter, number, and symbol"
		)
		String newPassword,
		@NotBlank String confirmPassword
	) {
	}
}
