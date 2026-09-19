ALTER TABLE practice_administration_event DROP CONSTRAINT practice_administration_event_type_check;
ALTER TABLE practice_administration_event ADD CONSTRAINT practice_administration_event_type_check
    CHECK (event_type IN (
        'DISPLAY_NAME_UPDATED', 'PRACTICE_SUSPENDED', 'PRACTICE_REACTIVATED',
        'ADMIN_TEMPORARY_PASSWORD_ISSUED', 'ADMIN_PASSWORD_SET', 'SYNTHETIC_CLINICAL_ENABLED'
    ));
