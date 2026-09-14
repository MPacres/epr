package ph.epr.api.platformoperations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeAdministratorSetup;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.platformoperations.application.TenantAdministrationService;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.TenantCreationRequest;

@ExtendWith(MockitoExtension.class)
class TenantProvisioningControllerTests {

	@Mock TenantProvisioningService provisioningService;
	@Mock TenantAdministrationService administrationService;
	@Mock PlatformUserDirectory users;
	@Mock Authentication authentication;
	@Captor ArgumentCaptor<TenantCreationRequest> requestCaptor;

	@Test
	void submitsTheExplicitInitialAdministratorWithoutConvertingProviderAuthority() {
		var actor = new PlatformUser(
			UUID.randomUUID(), "superadmin", "Superadmin", "hash", PlatformRole.SUPERADMIN, true, false
		);
		var practiceId = UUID.randomUUID();
		var administratorId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID();
		var tenant = activeTenant(practiceId, administratorId);
		when(authentication.getName()).thenReturn(actor.username());
		when(users.findByUsername(actor.username())).thenReturn(Optional.of(actor));
		var administrator = new PracticeAdministratorSetup(
			administratorId, "Alex Reyes", "alex.reyes@example.test",
			PracticeAdministratorSetup.SetupStatus.TEMPORARY_PASSWORD_ISSUED,
			"Temporary1!Password"
		);
		when(provisioningService.provision(org.mockito.ArgumentMatchers.eq(actor), requestCaptor.capture()))
			.thenReturn(new TenantProvisioningService.ProvisioningResult(tenant, true, administrator));
		var controller = new TenantProvisioningController(provisioningService, administrationService, users);

		var response = controller.create(
			authentication,
			idempotencyKey,
			new TenantProvisioningController.CreateTenantRequest(
				practiceId,
				"makati-clinic",
				"Makati Clinic",
				"Alex Reyes",
				"alex.reyes@example.test",
				new TenantProvisioningController.CreateInitialSiteRequest(
					UUID.randomUUID(), "Makati Clinic", "Medical Arts Building", "+63 917 000 0000",
					"PH", "1300000000", "1300000000", "1380300000"
				)
			)
		);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(requestCaptor.getValue().administratorEmail()).isEqualTo("alex.reyes@example.test");
		assertThat(requestCaptor.getValue().initialSite().localityCode()).isEqualTo("1380300000");
		assertThat(response.getBody().initialAdministratorUserId()).isEqualTo(administratorId);
		assertThat(response.getBody().temporaryPassword()).isEqualTo("Temporary1!Password");
		assertThat(response.getHeaders().getLocation())
			.hasPath("/api/v1/admin/practices/" + practiceId);
	}

	@Test
	void publicResponseNeverContainsDatabaseRoutesOrSecretReferences() {
		assertThat(Arrays.stream(TenantProvisioningController.TenantProvisioningResponse.class.getRecordComponents())
			.map(component -> component.getName()))
			.doesNotContain("databaseName", "jdbcUrl", "runtimeSecretReference", "migrationSecretReference");
		assertThat(Arrays.stream(TenantProvisioningController.TenantCreationResponse.class.getRecordComponents())
			.map(component -> component.getName()))
			.doesNotContain("databaseName", "jdbcUrl", "runtimeSecretReference", "migrationSecretReference");
	}

	private PracticeTenant activeTenant(UUID practiceId, UUID administratorId) {
		var now = OffsetDateTime.parse("2026-09-14T04:00:00Z");
		return new PracticeTenant(
			practiceId,
			"makati-clinic",
			"Makati Clinic",
			administratorId,
			PracticeProvisioningStatus.ACTIVE,
			PracticeServiceStatus.ENABLED,
			"private_database",
			"private_jdbc_url",
			"private_runtime_secret",
			"private_migration_secret",
			"1",
			1,
			null,
			null,
			null,
			null,
			null,
			now,
			now,
			1
		);
	}
}
