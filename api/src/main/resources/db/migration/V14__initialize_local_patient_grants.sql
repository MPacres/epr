-- One-time opt-in fixture upgrade only. Restarting the seeder must not restore revoked permissions.
UPDATE practice_clinician_site_grant SET patient_directory_read=true, patient_detail_read=true,
    patient_register=true, patient_demographic_edit=true
WHERE enabled AND reason='Opt-in local synthetic OB-GYN development fixture';
