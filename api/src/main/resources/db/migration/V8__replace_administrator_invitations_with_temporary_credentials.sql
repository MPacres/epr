ALTER TABLE platform_user_account
    ADD COLUMN password_reset_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT platform_user_account_password_reset_state_check
        CHECK (NOT password_reset_required OR enabled);

SELECT set_config('epr.platform_operations', 'true', true);

ALTER TABLE practice_administrator_invitation
    DROP CONSTRAINT practice_administrator_invitation_status_check;
ALTER TABLE practice_administrator_invitation
    RENAME TO practice_administrator_setup;
ALTER TABLE practice_administrator_setup
    RENAME COLUMN invitation_status TO setup_status;
ALTER TABLE practice_administrator_setup
    ALTER COLUMN setup_status TYPE VARCHAR(32);
ALTER TABLE practice_administrator_setup
    RENAME CONSTRAINT practice_administrator_invitation_pk TO practice_administrator_setup_pk;
ALTER TABLE practice_administrator_setup
    RENAME CONSTRAINT practice_administrator_invitation_practice_unique TO practice_administrator_setup_practice_unique;
ALTER TABLE practice_administrator_setup
    RENAME CONSTRAINT practice_administrator_invitation_user_fk TO practice_administrator_setup_user_fk;
ALTER TABLE practice_administrator_setup
    RENAME CONSTRAINT practice_administrator_invitation_requester_fk TO practice_administrator_setup_requester_fk;
ALTER TABLE practice_administrator_setup
    RENAME CONSTRAINT practice_administrator_invitation_email_check TO practice_administrator_setup_email_check;

UPDATE practice_administrator_setup
SET setup_status = 'PASSWORD_NOT_ISSUED'
WHERE setup_status = 'PREPARED';

ALTER TABLE practice_administrator_setup
    ADD CONSTRAINT practice_administrator_setup_status_check
        CHECK (setup_status IN (
            'PASSWORD_NOT_ISSUED',
            'TEMPORARY_PASSWORD_ISSUED',
            'PASSWORD_SET',
            'EXISTING_ACCOUNT'
        ));

ALTER POLICY practice_administrator_invitation_scope
    ON practice_administrator_setup
    RENAME TO practice_administrator_setup_scope;

ALTER TABLE practice_administration_event
    DROP CONSTRAINT practice_administration_event_type_check;
ALTER TABLE practice_administration_event
    ADD CONSTRAINT practice_administration_event_type_check
        CHECK (event_type IN (
            'DISPLAY_NAME_UPDATED',
            'PRACTICE_SUSPENDED',
            'PRACTICE_REACTIVATED',
            'ADMIN_TEMPORARY_PASSWORD_ISSUED',
            'ADMIN_PASSWORD_SET'
        ));

COMMENT ON COLUMN platform_user_account.password_reset_required IS
    'True when a local account authenticated with a temporary password and must replace it before other API access.';
COMMENT ON TABLE practice_administrator_setup IS
    'Initial Practice-administrator identity setup. Temporary passwords are stored only as password hashes and are never recoverable.';
COMMENT ON COLUMN practice_administrator_setup.setup_status IS
    'Support-assisted credential state; no email-delivery lifecycle is implied.';
