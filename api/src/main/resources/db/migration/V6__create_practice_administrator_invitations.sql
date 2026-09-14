CREATE TABLE practice_administrator_invitation (
    id UUID NOT NULL,
    practice_id UUID NOT NULL,
    user_id UUID NOT NULL,
    email VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    invitation_status VARCHAR(24) NOT NULL,
    requested_by_user_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT practice_administrator_invitation_pk PRIMARY KEY (id),
    CONSTRAINT practice_administrator_invitation_practice_unique UNIQUE (practice_id),
    CONSTRAINT practice_administrator_invitation_user_fk
        FOREIGN KEY (user_id) REFERENCES platform_user_account (id),
    CONSTRAINT practice_administrator_invitation_requester_fk
        FOREIGN KEY (requested_by_user_id) REFERENCES platform_user_account (id),
    CONSTRAINT practice_administrator_invitation_status_check
        CHECK (invitation_status IN ('PREPARED', 'EXISTING_ACCOUNT')),
    CONSTRAINT practice_administrator_invitation_email_check
        CHECK (email = lower(email) AND position('@' IN email) > 1)
);

COMMENT ON TABLE practice_administrator_invitation IS
    'Prepared initial Practice-administrator identity setup. PREPARED does not claim that an email was delivered.';

ALTER TABLE practice_administrator_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_administrator_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_administrator_invitation_scope ON practice_administrator_invitation
    USING (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    )
    WITH CHECK (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    );
