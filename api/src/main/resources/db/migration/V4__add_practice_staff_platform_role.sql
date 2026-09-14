ALTER TABLE platform_user_account
    DROP CONSTRAINT platform_user_account_role_check;

ALTER TABLE platform_user_account
    ADD CONSTRAINT platform_user_account_role_check
        CHECK (role IN ('SUPERADMIN', 'PROVIDER_SUPPORT', 'PRACTICE_STAFF', 'CLINICIAN'));

COMMENT ON COLUMN platform_user_account.role IS
    'Global account category only. Practice roles and clinical access remain separately authorized through memberships.';
