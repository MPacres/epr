-- Practices enabled after V18 must receive the same Practice-level list capabilities.
-- This intentionally creates no user grants.
INSERT INTO practice_clinical_chart_capability(practice_id, source_kind, enabled)
SELECT capability.practice_id, kind.source_kind, TRUE
FROM practice_clinical_capability capability
CROSS JOIN (VALUES ('ALLERGY'), ('MEDICATION_USE'), ('PROBLEM')) kind(source_kind)
WHERE capability.enabled
ON CONFLICT (practice_id, source_kind) DO UPDATE SET enabled = TRUE;
