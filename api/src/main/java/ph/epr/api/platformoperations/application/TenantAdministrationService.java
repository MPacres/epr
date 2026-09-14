package ph.epr.api.platformoperations.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeAdministrationPage;
import ph.epr.api.identitytenancy.PracticeAdministrationRegistry;
import ph.epr.api.identitytenancy.PracticeAdministrationView;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;

@Service
public class TenantAdministrationService {
	private final PracticeAdministrationRegistry registry;
	private final PlatformUserDirectory users;
	private final Clock clock;

	public TenantAdministrationService(
		PracticeAdministrationRegistry registry,
		PlatformUserDirectory users,
		Clock clock
	) {
		this.registry = registry;
		this.users = users;
		this.clock = clock;
	}

	public PracticeAdministrationPage list(
		PlatformUser actor,
		String search,
		PracticeProvisioningStatus provisioningStatus,
		PracticeServiceStatus serviceStatus,
		int page,
		int size
	) {
		requireSuperadmin(actor);
		if (page < 0) throw new IllegalArgumentException("Page must be zero or greater.");
		if (size < 1 || size > 100) throw new IllegalArgumentException("Page size must be between 1 and 100.");
		var normalizedSearch = search == null ? null : search.strip();
		if (normalizedSearch != null && normalizedSearch.length() > 160) {
			throw new IllegalArgumentException("Practice search must be 160 characters or fewer.");
		}
		return registry.list(normalizedSearch, provisioningStatus, serviceStatus, page, size);
	}

	public PracticeAdministrationView get(PlatformUser actor, UUID practiceId) {
		requireSuperadmin(actor);
		return registry.findAdministrationView(practiceId)
			.orElseThrow(() -> new TenantNotFoundException(practiceId));
	}

	public PracticeAdministrationView updateDisplayName(
		PlatformUser actor,
		UUID practiceId,
		String displayName,
		long expectedVersion,
		UUID idempotencyKey
	) {
		requireSuperadmin(actor);
		var normalized = displayName == null ? "" : displayName.strip();
		if (normalized.length() < 2 || normalized.length() > 160) {
			throw new IllegalArgumentException("Practice name must be between 2 and 160 characters.");
		}
		requireVersion(expectedVersion);
		return registry.updateDisplayName(
			practiceId, normalized, expectedVersion, idempotencyKey, actor.id(), now()
		);
	}

	public PracticeAdministrationView changeServiceStatus(
		PlatformUser actor,
		UUID practiceId,
		PracticeServiceStatus targetStatus,
		String reason,
		long expectedVersion,
		UUID idempotencyKey
	) {
		requireSuperadmin(actor);
		var normalizedReason = reason == null ? "" : reason.strip();
		if (normalizedReason.length() < 8 || normalizedReason.length() > 500) {
			throw new IllegalArgumentException("The management reason must be between 8 and 500 characters.");
		}
		requireVersion(expectedVersion);
		return registry.changeServiceStatus(
			practiceId, targetStatus, normalizedReason, expectedVersion,
			idempotencyKey, actor.id(), now()
		);
	}

	public IssuedTemporaryPassword issueTemporaryPassword(
		PlatformUser actor,
		UUID practiceId,
		String reason,
		long expectedVersion,
		UUID idempotencyKey
	) {
		requireSuperadmin(actor);
		var normalizedReason = reason == null ? "" : reason.strip();
		if (normalizedReason.length() < 8 || normalizedReason.length() > 500) {
			throw new IllegalArgumentException("The credential-issuance reason must be between 8 and 500 characters.");
		}
		requireVersion(expectedVersion);
		var issue = users.issueTemporaryPassword(
			practiceId, expectedVersion, idempotencyKey, actor.id(), normalizedReason, now()
		);
		return new IssuedTemporaryPassword(
			get(actor, practiceId), issue.temporaryPassword(), issue.newlyIssued()
		);
	}

	private void requireVersion(long expectedVersion) {
		if (expectedVersion < 0) throw new IllegalArgumentException("Practice version must be zero or greater.");
	}

	private void requireSuperadmin(PlatformUser actor) {
		if (actor == null || !actor.enabled() || actor.role() != PlatformRole.SUPERADMIN) {
			throw new TenantProvisioningForbiddenException();
		}
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock);
	}

	public record IssuedTemporaryPassword(
		PracticeAdministrationView practice,
		String temporaryPassword,
		boolean newlyIssued
	) {
	}
}
