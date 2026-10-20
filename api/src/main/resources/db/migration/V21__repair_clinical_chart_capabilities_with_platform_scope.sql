-- Flyway's non-superuser migration role remains subject to forced RLS.
-- Enter the platform-operations scope for this transaction so enabled
-- Practices are visible to the idempotent source-capability repair.
SELECT set_config('epr.platform_operations', 'true', TRUE);

INSERT INTO practice_clinical_chart_capability(practice_id, source_kind, enabled)
SELECT capability.practice_id, source.source_kind, TRUE
FROM practice_clinical_capability capability
CROSS JOIN (VALUES ('ALLERGY'), ('MEDICATION_USE'), ('PROBLEM')) source(source_kind)
WHERE capability.enabled
ON CONFLICT (practice_id, source_kind) DO UPDATE SET enabled = TRUE;
