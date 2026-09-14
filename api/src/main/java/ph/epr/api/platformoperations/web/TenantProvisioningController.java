package ph.epr.api.platformoperations.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.platformoperations.application.TenantProvisioningForbiddenException;
import ph.epr.api.platformoperations.application.TenantProvisioningService;
import ph.epr.api.platformoperations.domain.TenantCreationRequest;

@RestController
@Validated
@RequestMapping("/api/v1/admin/practices")
class TenantProvisioningController {

	private final TenantProvisioningService provisioningService;
	private final PlatformUserDirectory users;

	TenantProvisioningController(
		TenantProvisioningService provisioningService,
		PlatformUserDirectory users
	) {
		this.provisioningService = provisioningService;
		this.users = users;
	}

	@PostMapping
	@PreAuthorize("hasRole('SUPERADMIN')")
	ResponseEntity<TenantProvisioningResponse> create(
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
				request.initialAdministratorUserId(),
				idempotencyKey
			)
		);
		var status = result.provisioningPerformed()
			? HttpStatus.CREATED
			: result.tenant().isRoutable() ? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity.status(status)
			.location(URI.create("/api/v1/admin/practices/" + result.tenant().practiceId()))
			.body(TenantProvisioningResponse.from(result.tenant()));
	}

	@GetMapping("/{practiceId}")
	@PreAuthorize("hasRole('SUPERADMIN')")
	TenantProvisioningResponse get(Authentication authentication, @PathVariable UUID practiceId) {
		return TenantProvisioningResponse.from(provisioningService.get(actor(authentication), practiceId));
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
		@NotNull UUID initialAdministratorUserId
	) {
	}

	record TenantProvisioningResponse(
		UUID practiceId,
		String practiceCode,
		String displayName,
		UUID initialAdministratorUserId,
		String status,
		String schemaVersion,
		int provisioningAttempts,
		String failureCode,
		String failureMessage,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		long version
	) {
		static TenantProvisioningResponse from(PracticeTenant tenant) {
			return new TenantProvisioningResponse(
				tenant.practiceId(),
				tenant.code(),
				tenant.displayName(),
				tenant.initialAdministratorUserId(),
				tenant.status().name(),
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
}
