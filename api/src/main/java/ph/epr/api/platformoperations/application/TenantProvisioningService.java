package ph.epr.api.platformoperations.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PracticeProvisioningRegistry;
import ph.epr.api.identitytenancy.PracticeProvisioningRequest;
import ph.epr.api.identitytenancy.PracticeTenant;
import ph.epr.api.platformoperations.domain.TenantCreationRequest;

@Service
public class TenantProvisioningService {

	private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);
	private static final String FAILURE_CODE = "TENANT_DATABASE_PROVISIONING_FAILED";
	private static final String FAILURE_MESSAGE = "Tenant database provisioning did not complete; the tenant remains quarantined.";

	private final PracticeProvisioningRegistry registry;
	private final TenantDatabaseManager databaseManager;
	private final Clock clock;

	public TenantProvisioningService(
		PracticeProvisioningRegistry registry,
		TenantDatabaseManager databaseManager,
		Clock clock
	) {
		this.registry = registry;
		this.databaseManager = databaseManager;
		this.clock = clock;
	}

	public ProvisioningResult provision(PlatformUser actor, TenantCreationRequest request) {
		requireSuperadmin(actor);
		var registryRequest = new PracticeProvisioningRequest(
			request.practiceId(),
			request.practiceCode(),
			request.displayName(),
			request.initialAdministratorUserId(),
			request.idempotencyKey(),
			actor.id(),
			request.fingerprint()
		);
		var claim = registry.claim(registryRequest, now());
		if (!claim.provisioningRequired()) {
			return new ProvisioningResult(claim.tenant(), false);
		}

		try {
			var route = databaseManager.provision(claim.tenant());
			return new ProvisioningResult(
				registry.activate(
					request.practiceId(),
					request.idempotencyKey(),
					claim.tenant().provisioningAttempts(),
					actor.id(),
					route,
					now()
				),
				true
			);
		}
		catch (RuntimeException exception) {
			log.error("Tenant database provisioning failed for practice {}.", request.practiceId(), exception);
			var failureCode = exception instanceof TenantProvisioningException provisioningException
				? provisioningException.code()
				: FAILURE_CODE;
			var failureMessage = exception instanceof TenantProvisioningException
				? exception.getMessage()
				: FAILURE_MESSAGE;
			registry.quarantine(
				request.practiceId(),
				request.idempotencyKey(),
				claim.tenant().provisioningAttempts(),
				actor.id(),
				failureCode,
				failureMessage,
				now()
			);
			if (exception instanceof TenantProvisioningException provisioningException) {
				throw provisioningException;
			}
			throw new TenantProvisioningException(FAILURE_CODE, FAILURE_MESSAGE, exception);
		}
	}

	public PracticeTenant get(PlatformUser actor, UUID practiceId) {
		requireSuperadmin(actor);
		return registry.findByPracticeId(practiceId)
			.orElseThrow(() -> new TenantNotFoundException(practiceId));
	}

	private void requireSuperadmin(PlatformUser actor) {
		if (actor == null || !actor.enabled() || actor.role() != PlatformRole.SUPERADMIN) {
			throw new TenantProvisioningForbiddenException();
		}
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock);
	}

	public record ProvisioningResult(PracticeTenant tenant, boolean provisioningPerformed) {
	}
}
