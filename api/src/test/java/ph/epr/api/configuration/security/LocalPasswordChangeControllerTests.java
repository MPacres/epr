package ph.epr.api.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;

@ExtendWith(MockitoExtension.class)
class LocalPasswordChangeControllerTests {
	@Mock PlatformUserDirectory users;

	@Test
	void replacesTheAuthenticatedTemporaryCredential() {
		var now = OffsetDateTime.parse("2026-09-14T08:00:00Z");
		var key = UUID.randomUUID();
		var temporary = account(true);
		var updated = new PlatformUser(
			temporary.id(), temporary.username(), temporary.displayName(), "new-hash",
			temporary.role(), true, false
		);
		when(users.findByUsername(temporary.username())).thenReturn(Optional.of(temporary));
		when(users.completeRequiredPasswordChange(temporary.id(), "Private-Workspace9!", key, now)).thenReturn(updated);
		var controller = new LocalPasswordChangeController(
			users, Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC)
		);

		var response = controller.changePassword(
			UsernamePasswordAuthenticationToken.authenticated(temporary.username(), null, java.util.List.of()),
			key,
			new LocalPasswordChangeController.PasswordChangeRequest("Private-Workspace9!", "Private-Workspace9!")
		);

		assertThat(response.passwordResetRequired()).isFalse();
	}

	@Test
	void rejectsAMismatchedConfirmationBeforeUpdatingCredentials() {
		var controller = new LocalPasswordChangeController(users, Clock.systemUTC());
		assertThatThrownBy(() -> controller.changePassword(
			UsernamePasswordAuthenticationToken.authenticated("admin@example.test", null, java.util.List.of()),
			UUID.randomUUID(),
			new LocalPasswordChangeController.PasswordChangeRequest("Private-Workspace9!", "Different-Password8!")
		)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
	}

	private PlatformUser account(boolean resetRequired) {
		return new PlatformUser(
			UUID.randomUUID(), "admin@example.test", "Practice Admin", "hash",
			PlatformRole.PRACTICE_STAFF, true, resetRequired
		);
	}
}
