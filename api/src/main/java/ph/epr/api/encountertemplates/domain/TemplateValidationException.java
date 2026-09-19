package ph.epr.api.encountertemplates.domain;

public final class TemplateValidationException extends IllegalArgumentException {
    public TemplateValidationException(String code) { super(code); }
}
