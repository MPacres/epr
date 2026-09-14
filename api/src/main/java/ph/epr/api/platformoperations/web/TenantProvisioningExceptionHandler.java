package ph.epr.api.platformoperations.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ph.epr.api.identitytenancy.PracticeProvisioningConflictException;
import ph.epr.api.identitytenancy.PracticeAdministrationConflictException;
import ph.epr.api.platformoperations.application.TenantNotFoundException;
import ph.epr.api.platformoperations.application.TenantProvisioningException;
import ph.epr.api.platformoperations.application.TenantProvisioningForbiddenException;

@RestControllerAdvice(assignableTypes = TenantProvisioningController.class)
class TenantProvisioningExceptionHandler {

	@ExceptionHandler(PracticeProvisioningConflictException.class)
	ProblemDetail conflict(PracticeProvisioningConflictException exception) {
		return problem(HttpStatus.CONFLICT, "TENANT_PROVISIONING_CONFLICT", exception.getMessage());
	}

	@ExceptionHandler(PracticeAdministrationConflictException.class)
	ProblemDetail administrationConflict(PracticeAdministrationConflictException exception) {
		return problem(HttpStatus.CONFLICT, "PRACTICE_ADMINISTRATION_CONFLICT", exception.getMessage());
	}

	@ExceptionHandler(TenantProvisioningForbiddenException.class)
	ProblemDetail forbidden(TenantProvisioningForbiddenException exception) {
		return problem(HttpStatus.FORBIDDEN, "TENANT_PROVISIONING_FORBIDDEN", exception.getMessage());
	}

	@ExceptionHandler(TenantNotFoundException.class)
	ProblemDetail notFound(TenantNotFoundException exception) {
		return problem(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", exception.getMessage());
	}

	@ExceptionHandler(TenantProvisioningException.class)
	ProblemDetail provisioningFailure(TenantProvisioningException exception) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.code(), exception.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalidRequest(IllegalArgumentException exception) {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_TENANT_REQUEST", exception.getMessage());
	}

	private ProblemDetail problem(HttpStatus status, String code, String detail) {
		var problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:epr:problem:" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		return problem;
	}
}
