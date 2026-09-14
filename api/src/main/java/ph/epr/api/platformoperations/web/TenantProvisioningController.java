package ph.epr.api.platformoperations.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.identitytenancy.PracticeAdministrationPage;
import ph.epr.api.identitytenancy.PracticeAdministrationView;
import ph.epr.api.identitytenancy.PracticeProvisioningStatus;
import ph.epr.api.identitytenancy.PracticeServiceStatus;
import ph.epr.api.platformoperations.application.TenantAdministrationService;
import ph.epr.api.platformoperations.application.TenantProvisioningForbiddenException;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.InitialTenantSite;
import ph.epr.api.platformoperations.domain.TenantCreationRequest;

@RestController
@Validated
@RequestMapping("/api/v1/admin/practices")
class TenantProvisioningController {

	private final TenantProvisioningService provisioningService;
	private final TenantAdministrationService administrationService;
	private final PlatformUserDirectory users;

	TenantProvisioningController(
		TenantProvisioningService provisioningService,
		TenantAdministrationService administrationService,
		PlatformUserDirectory users
	) {
		this.provisioningService = provisioningService;
		this.administrationService = administrationService;
		this.users = users;
	}

	@PostMapping
	@PreAuthorize("hasRole('SUPERADMIN')")
	ResponseEntity<TenantCreationResponse> create(
		Authentication authentication,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey,
		@Valid @RequestBody CreateTenantRequest request
	) {
		var result = provisioningService.provision(
			actor(authentication),
			new TenantCreationRequest(
				request.practiceId(),
				request.practiceCode(),
				request.displayName(),
				request.administratorName(),
				request.administratorEmail(),
				new InitialTenantSite(
					request.initialSite().siteId(),
					request.initialSite().name(),
					request.initialSite().facilityName(),
					request.initialSite().contactNumber(),
					request.initialSite().countryCode(),
					request.initialSite().regionCode(),
					request.initialSite().provinceAreaCode(),
					request.initialSite().localityCode()
				),
				idempotencyKey
			)
		);
		var status = result.provisioningPerformed()
			? HttpStatus.CREATED
			: result.tenant().isRoutable() ? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity.status(status)
			.location(URI.create("/api/v1/admin/practices/" + result.tenant().practiceId()))
			.body(TenantCreationResponse.from(result, request.initialSite().siteId()));
	}

	@GetMapping
	@PreAuthorize("hasRole('SUPERADMIN')")
	PracticeDirectoryResponse list(
		Authentication authentication,
		@RequestParam(required = false) String search,
		@RequestParam(required = false) PracticeProvisioningStatus provisioningStatus,
		@RequestParam(required = false) PracticeServiceStatus serviceStatus,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "25") int size
	) {
		return PracticeDirectoryResponse.from(administrationService.list(
			actor(authentication), search, provisioningStatus, serviceStatus, page, size
		));
	}

	@GetMapping("/{practiceId}")
	@PreAuthorize("hasRole('SUPERADMIN')")
	TenantProvisioningResponse get(Authentication authentication, @PathVariable UUID practiceId) {
		return TenantProvisioningResponse.from(administrationService.get(actor(authentication), practiceId));
	}

	@PatchMapping("/{practiceId}")
	@PreAuthorize("hasRole('SUPERADMIN')")
	TenantProvisioningResponse update(
		Authentication authentication,
		@PathVariable UUID practiceId,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey,
		@Valid @RequestBody UpdatePracticeRequest request
	) {
		return TenantProvisioningResponse.from(administrationService.updateDisplayName(
			actor(authentication), practiceId, request.displayName(), request.version(), idempotencyKey
		));
	}

	@PostMapping("/{practiceId}/suspension")
	@PreAuthorize("hasRole('SUPERADMIN')")
	TenantProvisioningResponse suspend(
		Authentication authentication,
		@PathVariable UUID practiceId,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey,
		@Valid @RequestBody PracticeLifecycleRequest request
	) {
		return TenantProvisioningResponse.from(administrationService.changeServiceStatus(
			actor(authentication), practiceId, PracticeServiceStatus.SUSPENDED,
			request.reason(), request.version(), idempotencyKey
		));
	}

	@PostMapping("/{practiceId}/reactivation")
	@PreAuthorize("hasRole('SUPERADMIN')")
	TenantProvisioningResponse reactivate(
		Authentication authentication,
		@PathVariable UUID practiceId,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey,
		@Valid @RequestBody PracticeLifecycleRequest request
	) {
		return TenantProvisioningResponse.from(administrationService.changeServiceStatus(
			actor(authentication), practiceId, PracticeServiceStatus.ENABLED,
			request.reason(), request.version(), idempotencyKey
		));
	}

	@PostMapping("/{practiceId}/administrator/temporary-password")
	@PreAuthorize("hasRole('SUPERADMIN')")
	AdministratorTemporaryPasswordResponse issueTemporaryPassword(
		Authentication authentication,
		@PathVariable UUID practiceId,
		@RequestHeader("Idempotency-Key") UUID idempotencyKey,
		@Valid @RequestBody PracticeLifecycleRequest request
	) {
		var result = administrationService.issueTemporaryPassword(
			actor(authentication), practiceId, request.reason(), request.version(), idempotencyKey
		);
		return new AdministratorTemporaryPasswordResponse(
			TenantProvisioningResponse.from(result.practice()),
			result.temporaryPassword(),
			result.newlyIssued()
		);
	}

	private PlatformUser actor(Authentication authentication) {
		return users.findByUsername(authentication.getName())
			.orElseThrow(TenantProvisioningForbiddenException::new);
	}

	record CreateTenantRequest(
		@NotNull UUID practiceId,
		@NotBlank
		@Size(max = 32)
		@Pattern(regexp = "[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?")
		String practiceCode,
		@NotBlank @Size(min = 2, max = 160) String displayName,
		@NotBlank @Size(min = 2, max = 160) String administratorName,
		@NotBlank @Email @Size(max = 160) String administratorEmail,
		@NotNull @Valid CreateInitialSiteRequest initialSite
	) {
	}

	record CreateInitialSiteRequest(
		@NotNull UUID siteId,
		@NotBlank @Size(min = 2, max = 120) String name,
		@Size(max = 160) String facilityName,
		@Size(max = 30) String contactNumber,
		@NotBlank @Pattern(regexp = "PH") String countryCode,
		@NotBlank @Pattern(regexp = "[0-9]{10}") String regionCode,
		@NotBlank @Pattern(regexp = "[0-9]{10}") String provinceAreaCode,
		@NotBlank @Pattern(regexp = "[0-9]{10}") String localityCode
	) {
	}

	record UpdatePracticeRequest(
		@NotBlank @Size(min = 2, max = 160) String displayName,
		long version
	) {
	}

	record PracticeLifecycleRequest(
		@NotBlank @Size(min = 8, max = 500) String reason,
		long version
	) {
	}

	record TenantCreationResponse(
		UUID practiceId,
		String practiceCode,
		String displayName,
		UUID initialAdministratorUserId,
		String administratorSetupStatus,
		String temporaryPassword,
		UUID initialSiteId,
		String status,
		String serviceStatus,
		String schemaVersion,
		int provisioningAttempts,
		String failureCode,
		String failureMessage,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		long version
	) {
		static TenantCreationResponse from(TenantProvisioningService.ProvisioningResult result, UUID initialSiteId) {
			var tenant = result.tenant();
			return new TenantCreationResponse(
				tenant.practiceId(),
				tenant.code(),
				tenant.displayName(),
				tenant.initialAdministratorUserId(),
				result.administrator().setupStatus().name(),
				result.administrator().temporaryPassword(),
				initialSiteId,
				tenant.status().name(),
				tenant.serviceStatus().name(),
				tenant.schemaVersion(),
				tenant.provisioningAttempts(),
				tenant.failureCode(),
				tenant.failureMessage(),
				tenant.createdAt(),
				tenant.updatedAt(),
				tenant.version()
			);
		}
	}

	record TenantProvisioningResponse(
		UUID practiceId,
		String practiceCode,
		String displayName,
		UUID initialAdministratorUserId,
		String administratorName,
		String administratorEmail,
		String administratorSetupStatus,
		String status,
		String serviceStatus,
		String schemaVersion,
		int provisioningAttempts,
		String failureCode,
		String failureMessage,
		OffsetDateTime suspendedAt,
		String suspensionReason,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		long version
	) {
		static TenantProvisioningResponse from(PracticeAdministrationView view) {
			var tenant = view.tenant();
			return new TenantProvisioningResponse(
				tenant.practiceId(),
				tenant.code(),
				tenant.displayName(),
				tenant.initialAdministratorUserId(),
				view.administratorName(),
				view.administratorEmail(),
				view.administratorSetupStatus(),
				tenant.status().name(),
				tenant.serviceStatus().name(),
				tenant.schemaVersion(),
				tenant.provisioningAttempts(),
				tenant.failureCode(),
				tenant.failureMessage(),
				tenant.suspendedAt(),
				tenant.suspensionReason(),
				tenant.createdAt(),
				tenant.updatedAt(),
				tenant.version()
			);
		}
	}

	record AdministratorTemporaryPasswordResponse(
		TenantProvisioningResponse practice,
		String temporaryPassword,
		boolean newlyIssued
	) {
	}

	record PracticeDirectoryResponse(
		java.util.List<TenantProvisioningResponse> items,
		long totalElements,
		int totalPages,
		int page,
		int size
	) {
		static PracticeDirectoryResponse from(PracticeAdministrationPage result) {
			return new PracticeDirectoryResponse(
				result.items().stream().map(TenantProvisioningResponse::from).toList(),
				result.totalElements(), result.totalPages(), result.page(), result.size()
			);
		}
	}
}
