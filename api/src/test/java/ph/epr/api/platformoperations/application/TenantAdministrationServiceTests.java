package ph.epr.api.platformoperations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeAdministrationPage;
import ph.epr.api.identitytenancy.PracticeAdministrationRegistry;
import ph.epr.api.identitytenancy.PracticeAdministrationView;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.identitytenancy.PracticeTenant;

@ExtendWith(MockitoExtension.class)
class TenantAdministrationServiceTests {
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-14T04:00:00Z");

	@Mock PracticeAdministrationRegistry registry;
	@Mock PlatformUserDirectory users;
	private TenantAdministrationService service;

	@BeforeEach
	void setUp() {
		service = new TenantAdministrationService(
			registry,
			users,
			Clock.fixed(Instant.parse("2026-09-14T04:00:00Z"), ZoneOffset.UTC)
		);
	}

	@Test
	void listsOnlyThroughAnEnabledSuperadminBoundary() {
		var page = new PracticeAdministrationPage(List.of(view()), 1, 0, 25);
		when(registry.list("Santos", PracticeProvisioningStatus.ACTIVE, PracticeServiceStatus.ENABLED, 0, 25))
			.thenReturn(page);

		assertThat(service.list(
			superadmin(), "  Santos  ", PracticeProvisioningStatus.ACTIVE,
			PracticeServiceStatus.ENABLED, 0, 25
		)).isEqualTo(page);

		assertThatThrownBy(() -> service.list(
			new PlatformUser(UUID.randomUUID(), "support", "Support", "hash", PlatformRole.PROVIDER_SUPPORT, true, false),
			null, null, null, 0, 25
		)).isInstanceOf(TenantProvisioningForbiddenException.class);
	}

	@Test
	void updatesTheDisplayNameWithVersionAndIdempotencyContext() {
		var practiceId = view().tenant().practiceId();
		var key = UUID.randomUUID();
		var actor = superadmin();
		when(registry.updateDisplayName(practiceId, "Santos Clinic", 3, key, actor.id(), NOW))
			.thenReturn(view());

		service.updateDisplayName(actor, practiceId, "  Santos Clinic  ", 3, key);

		verify(registry).updateDisplayName(practiceId, "Santos Clinic", 3, key, actor.id(), NOW);
	}

	@Test
	void requiresAnAuditableReasonForServiceAccessChanges() {
		assertThatThrownBy(() -> service.changeServiceStatus(
			superadmin(), view().tenant().practiceId(), PracticeServiceStatus.SUSPENDED,
			"short", 3, UUID.randomUUID()
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reason");
	}

	private PlatformUser superadmin() {
		return new PlatformUser(
			UUID.randomUUID(), "superadmin", "Superadmin", "hash", PlatformRole.SUPERADMIN, true, false
		);
	}

	private PracticeAdministrationView view() {
		var tenant = new PracticeTenant(
			UUID.fromString("8fb6b6e2-0e49-4879-80d7-e48ef5ab1259"),
			"santos-clinic", "Santos Clinic", UUID.randomUUID(),
			PracticeProvisioningStatus.ACTIVE, PracticeServiceStatus.ENABLED,
			"database", "jdbc:postgresql://server/database", "runtime", "migration", "2", 1,
			null, null, null, null, null, NOW, NOW, 3
		);
		return new PracticeAdministrationView(
			tenant, "Alex Reyes", "alex.reyes@example.test", "TEMPORARY_PASSWORD_ISSUED"
		);
	}
}
