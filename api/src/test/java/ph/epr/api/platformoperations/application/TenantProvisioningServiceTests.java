package ph.epr.api.platformoperations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import ph.epr.api.identitytenancy.PracticeAdministratorSetup;
import ph.epr.api.identitytenancy.PracticeProvisioningRegistry;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.TenantDatabaseRoute;
import ph.epr.api.platformoperations.domain.TenantCreationRequest;
import ph.epr.api.platformoperations.domain.InitialTenantSite;

@ExtendWith(MockitoExtension.class)
class TenantProvisioningServiceTests {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-14T04:00:00Z");

	@Mock PracticeProvisioningRegistry registry;
	@Mock PlatformUserDirectory users;
	@Mock TenantDatabaseManager databaseManager;

	private TenantProvisioningService service;

	@BeforeEach
	void setUp() {
		service = new TenantProvisioningService(
			registry,
			users,
			databaseManager,
			Clock.fixed(Instant.parse("2026-09-14T04:00:00Z"), ZoneOffset.UTC)
		);
	}

	@Test
	void provisionsAndActivatesAClaimedTenant() {
		var request = request();
		var tenant = tenant(request, PracticeProvisioningStatus.PROVISIONING);
		var route = new TenantDatabaseRoute("database", "jdbc-url", "runtime-ref", "migration-ref", "1");
		var active = activeTenant(request, route);
		stubAdministrator(request);
		when(registry.claim(any(), any())).thenReturn(new PracticeProvisioningRegistry.ProvisioningClaim(tenant, true));
		when(databaseManager.provision(tenant, request.initialSite())).thenReturn(route);
		var actor = superadmin();
		when(registry.activate(request.practiceId(), request.idempotencyKey(), 1, actor.id(), route, NOW)).thenReturn(active);

		var result = service.provision(actor, request);

		assertThat(result.provisioningPerformed()).isTrue();
		assertThat(result.tenant().isRoutable()).isTrue();
	}

	@Test
	void returnsAnExistingClaimWithoutDuplicatingDatabaseWork() {
		var request = request();
		var tenant = tenant(request, PracticeProvisioningStatus.PROVISIONING);
		stubAdministrator(request);
		when(registry.claim(any(), any())).thenReturn(new PracticeProvisioningRegistry.ProvisioningClaim(tenant, false));

		var result = service.provision(superadmin(), request);

		assertThat(result.provisioningPerformed()).isFalse();
		verify(databaseManager, never()).provision(any(), any());
	}

	@Test
	void quarantinesAProvisioningFailureWithASafeOperationalCode() {
		var request = request();
		var tenant = tenant(request, PracticeProvisioningStatus.PROVISIONING);
		stubAdministrator(request);
		when(registry.claim(any(), any())).thenReturn(new PracticeProvisioningRegistry.ProvisioningClaim(tenant, true));
		when(databaseManager.provision(tenant, request.initialSite())).thenThrow(
			new TenantProvisioningException("TENANT_SCHEMA_MIGRATION_FAILED", "Tenant schema migration failed.")
		);

		var actor = superadmin();
		assertThatThrownBy(() -> service.provision(actor, request))
			.isInstanceOf(TenantProvisioningException.class)
			.hasMessage("Tenant schema migration failed.");
		verify(registry).quarantine(
			request.practiceId(),
			request.idempotencyKey(),
			1,
			actor.id(),
			"TENANT_SCHEMA_MIGRATION_FAILED",
			"Tenant schema migration failed.",
			NOW
		);
	}

	@Test
	void rechecksCurrentPlatformAuthorityInsideTheApplicationBoundary() {
		var clinician = new PlatformUser(
			UUID.randomUUID(), "doctor", "Doctor", "hash", PlatformRole.CLINICIAN, true, false
		);

		assertThatThrownBy(() -> service.provision(clinician, request()))
			.isInstanceOf(TenantProvisioningForbiddenException.class);
		verify(registry, never()).claim(any(), any());
	}

	@Test
	void looksUpProvisioningStatusWithoutExposingARouteThroughTheWebContract() {
		var request = request();
		var tenant = tenant(request, PracticeProvisioningStatus.QUARANTINED);
		when(registry.findByPracticeId(request.practiceId())).thenReturn(Optional.of(tenant));

		assertThat(service.get(superadmin(), request.practiceId())).isEqualTo(tenant);
	}

	private TenantCreationRequest request() {
		return new TenantCreationRequest(
			UUID.randomUUID(),
			"makati-family-clinic",
			"Makati Family Clinic",
			"Alex Reyes",
			"alex.reyes@example.test",
			new InitialTenantSite(
				UUID.randomUUID(), "Makati Clinic", "Medical Arts Building", "+63 917 000 0000",
				"PH", "1300000000", "1300000000", "1380300000"
			),
			UUID.randomUUID()
		);
	}

	private void stubAdministrator(TenantCreationRequest request) {
		when(users.preparePracticeAdministrator(
			org.mockito.ArgumentMatchers.eq(request.practiceId()),
			org.mockito.ArgumentMatchers.eq(request.administratorName()),
			org.mockito.ArgumentMatchers.eq(request.administratorEmail()),
			any(),
			any()
		)).thenReturn(administrator());
	}

	private PracticeAdministratorSetup administrator() {
		return new PracticeAdministratorSetup(
			UUID.fromString("2a570acc-39a0-4b50-91bc-8b6ca2df0f2d"),
			"Alex Reyes",
			"alex.reyes@example.test",
			PracticeAdministratorSetup.SetupStatus.TEMPORARY_PASSWORD_ISSUED,
			"Temporary1!Password"
		);
	}

	private PlatformUser superadmin() {
		return new PlatformUser(
			UUID.randomUUID(), "superadmin", "Superadmin", "hash", PlatformRole.SUPERADMIN, true, false
		);
	}

	private PracticeTenant tenant(TenantCreationRequest request, PracticeProvisioningStatus status) {
		return new PracticeTenant(
			request.practiceId(),
			request.practiceCode(),
			request.displayName(),
			administrator().userId(),
			status,
			PracticeServiceStatus.ENABLED,
			null,
			null,
			null,
			null,
			null,
			1,
			null,
			null,
			null,
			null,
			null,
			NOW,
			NOW,
			0
		);
	}

	private PracticeTenant activeTenant(TenantCreationRequest request, TenantDatabaseRoute route) {
		return new PracticeTenant(
			request.practiceId(),
			request.practiceCode(),
			request.displayName(),
			administrator().userId(),
			PracticeProvisioningStatus.ACTIVE,
			PracticeServiceStatus.ENABLED,
			route.databaseName(),
			route.jdbcUrl(),
			route.runtimeSecretReference(),
			route.migrationSecretReference(),
			route.schemaVersion(),
			1,
			null,
			null,
			null,
			null,
			null,
			NOW,
			NOW,
			1
		);
	}
}
