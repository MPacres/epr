-- Repair Practices whose synthetic clinical capability was enabled after the
-- original capability backfill ran but before activation created source rows.
-- This intentionally creates no individual clinician grants.
INSERT INTO practice_clinical_chart_capability(practice_id, source_kind, enabled)
SELECT capability.practice_id, source.source_kind, TRUE
FROM practice_clinical_capability capability
CROSS JOIN (VALUES ('ALLERGY'), ('MEDICATION_USE'), ('PROBLEM')) source(source_kind)
WHERE capability.enabled
ON CONFLICT (practice_id, source_kind) DO UPDATE SET enabled = TRUE;
