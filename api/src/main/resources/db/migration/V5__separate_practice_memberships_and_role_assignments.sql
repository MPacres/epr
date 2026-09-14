CREATE TABLE practice_role_assignment (
    practice_id UUID NOT NULL,
    user_id UUID NOT NULL,
    practice_role VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT practice_role_assignment_pk PRIMARY KEY (practice_id, user_id, practice_role),
    CONSTRAINT practice_role_assignment_membership_fk
        FOREIGN KEY (practice_id, user_id)
            REFERENCES practice_membership (practice_id, user_id),
    CONSTRAINT practice_role_assignment_role_check
        CHECK (practice_role IN ('PRACTICE_ADMINISTRATOR', 'CLINICIAN', 'NURSE', 'SECRETARY'))
);

INSERT INTO practice_role_assignment (
    practice_id, user_id, practice_role, enabled, created_at, updated_at
)
SELECT practice_id, user_id, practice_role, enabled, created_at, updated_at
FROM practice_membership;

ALTER TABLE practice_membership
    DROP COLUMN practice_role;

ALTER TABLE practice_role_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_role_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_role_assignment_scope ON practice_role_assignment
    USING (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    )
    WITH CHECK (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    );

COMMENT ON TABLE practice_membership IS
    'A user-to-Practice relationship. Permissions are assigned separately so one member may hold multiple roles.';
COMMENT ON TABLE practice_role_assignment IS
    'Practice-scoped roles for a membership; administrative roles do not imply clinical-record access.';
