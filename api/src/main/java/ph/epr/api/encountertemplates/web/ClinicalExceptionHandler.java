package ph.epr.api.encountertemplates.web;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.encountertemplates.domain.TemplateValidationException;

@RestControllerAdvice(basePackages = {"ph.epr.api.encountertemplates.web", "ph.epr.api.patientregistry.web", "ph.epr.api.clinicalchart.web", "ph.epr.api.schedulingqueue.web", "ph.epr.api.carecoordination.web", "ph.epr.api.platformoperations.web", "ph.epr.api.ordersresults.web", "ph.epr.api.prescribing.web"})
class ClinicalExceptionHandler {
    record Error(String code, UUID correlationId) {}
    @ExceptionHandler(ClinicalAccessException.class)
    ResponseEntity<Error> access(ClinicalAccessException e) { return ResponseEntity.status(e.status()).body(new Error(e.getMessage(), UUID.randomUUID())); }
    @ExceptionHandler(TemplateValidationException.class)
    ResponseEntity<Error> validation(TemplateValidationException e) { return ResponseEntity.unprocessableContent().body(new Error(e.getMessage(), UUID.randomUUID())); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Error> malformed() { return ResponseEntity.unprocessableContent().body(new Error("INVALID_COMMAND_FIELDS",UUID.randomUUID())); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> conflict() { return ResponseEntity.status(409).body(new Error("RECORD_CONFLICT", UUID.randomUUID())); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Error> unavailable() { return ResponseEntity.status(503).body(new Error("CLINICAL_SERVICE_UNAVAILABLE", UUID.randomUUID())); }
}
