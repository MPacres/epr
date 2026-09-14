CREATE TABLE practice_tenant_registry (
    practice_id UUID NOT NULL,
    practice_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    initial_administrator_user_id UUID NOT NULL,
    provisioning_status VARCHAR(24) NOT NULL,
    provisioning_idempotency_key UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    requested_by_user_id UUID NOT NULL,
    provisioning_lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    database_name VARCHAR(63),
    jdbc_url VARCHAR(1000),
    runtime_secret_reference VARCHAR(500),
    migration_secret_reference VARCHAR(500),
    schema_version VARCHAR(50),
    provisioning_attempts INTEGER NOT NULL DEFAULT 1,
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT practice_tenant_registry_pk PRIMARY KEY (practice_id),
    CONSTRAINT practice_tenant_registry_code_unique UNIQUE (practice_code),
    CONSTRAINT practice_tenant_registry_idempotency_unique UNIQUE (provisioning_idempotency_key),
    CONSTRAINT practice_tenant_registry_administrator_fk
        FOREIGN KEY (initial_administrator_user_id) REFERENCES platform_user_account (id),
    CONSTRAINT practice_tenant_registry_requester_fk
        FOREIGN KEY (requested_by_user_id) REFERENCES platform_user_account (id),
    CONSTRAINT practice_tenant_registry_code_check
        CHECK (practice_code ~ '^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$'),
    CONSTRAINT practice_tenant_registry_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT practice_tenant_registry_status_check
        CHECK (provisioning_status IN ('PROVISIONING', 'ACTIVE', 'QUARANTINED')),
    CONSTRAINT practice_tenant_registry_attempts_check CHECK (provisioning_attempts > 0),
    CONSTRAINT practice_tenant_registry_database_name_check CHECK (
        database_name IS NULL OR database_name ~ '^[a-z][a-z0-9_]{0,62}$'
    ),
    CONSTRAINT practice_tenant_registry_jdbc_url_check CHECK (
        jdbc_url IS NULL OR jdbc_url LIKE 'jdbc:postgresql://%'
    ),
    CONSTRAINT practice_tenant_registry_lifecycle_check CHECK (
        (provisioning_status = 'PROVISIONING'
            AND provisioning_lease_expires_at IS NOT NULL
            AND database_name IS NULL
            AND jdbc_url IS NULL
            AND runtime_secret_reference IS NULL
            AND migration_secret_reference IS NULL
            AND schema_version IS NULL
            AND failure_code IS NULL
            AND failure_message IS NULL)
        OR (provisioning_status = 'ACTIVE'
            AND provisioning_lease_expires_at IS NULL
            AND database_name IS NOT NULL
            AND jdbc_url IS NOT NULL
            AND runtime_secret_reference IS NOT NULL
            AND migration_secret_reference IS NOT NULL
            AND schema_version IS NOT NULL
            AND failure_code IS NULL
            AND failure_message IS NULL)
        OR (provisioning_status = 'QUARANTINED'
            AND provisioning_lease_expires_at IS NULL
            AND database_name IS NULL
            AND jdbc_url IS NULL
            AND runtime_secret_reference IS NULL
            AND migration_secret_reference IS NULL
            AND schema_version IS NULL
            AND failure_code IS NOT NULL
            AND failure_message IS NOT NULL)
    )
);

CREATE TABLE practice_membership (
    practice_id UUID NOT NULL,
    user_id UUID NOT NULL,
    practice_role VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT practice_membership_pk PRIMARY KEY (practice_id, user_id),
    CONSTRAINT practice_membership_practice_fk
        FOREIGN KEY (practice_id) REFERENCES practice_tenant_registry (practice_id),
    CONSTRAINT practice_membership_user_fk
        FOREIGN KEY (user_id) REFERENCES platform_user_account (id),
    CONSTRAINT practice_membership_role_check
        CHECK (practice_role IN ('PRACTICE_ADMINISTRATOR', 'CLINICIAN', 'NURSE', 'SECRETARY'))
);

CREATE TABLE tenant_provisioning_event (
    id UUID NOT NULL,
    practice_id UUID NOT NULL,
    attempt INTEGER NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_user_id UUID NOT NULL,
    detail_code VARCHAR(80),
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT tenant_provisioning_event_pk PRIMARY KEY (id),
    CONSTRAINT tenant_provisioning_event_practice_fk
        FOREIGN KEY (practice_id) REFERENCES practice_tenant_registry (practice_id),
    CONSTRAINT tenant_provisioning_event_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES platform_user_account (id),
    CONSTRAINT tenant_provisioning_event_attempt_check CHECK (attempt > 0),
    CONSTRAINT tenant_provisioning_event_type_check CHECK (
        event_type IN (
            'PROVISIONING_STARTED',
            'PROVISIONING_RETRIED',
            'PROVISIONING_SUCCEEDED',
            'PROVISIONING_QUARANTINED'
        )
    )
);

CREATE INDEX tenant_provisioning_event_practice_time_idx
    ON tenant_provisioning_event (practice_id, occurred_at);

COMMENT ON TABLE practice_tenant_registry IS
    'Trusted control-plane routes and provisioning state for Practice tenants; never client supplied.';
COMMENT ON TABLE tenant_provisioning_event IS
    'Append-only operational history for recoverable tenant provisioning attempts.';

CREATE TABLE local_tenant_database_credential (
    secret_reference VARCHAR(500) NOT NULL,
    practice_id UUID NOT NULL,
    credential_purpose VARCHAR(20) NOT NULL,
    database_username VARCHAR(63) NOT NULL,
    encrypted_password BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT local_tenant_database_credential_pk PRIMARY KEY (secret_reference),
    CONSTRAINT local_tenant_database_credential_practice_fk
        FOREIGN KEY (practice_id) REFERENCES practice_tenant_registry (practice_id),
    CONSTRAINT local_tenant_database_credential_tenant_purpose_unique
        UNIQUE (practice_id, credential_purpose),
    CONSTRAINT local_tenant_database_credential_purpose_check
        CHECK (credential_purpose IN ('RUNTIME', 'MIGRATION')),
    CONSTRAINT local_tenant_database_credential_nonce_check CHECK (octet_length(nonce) = 12)
);

COMMENT ON TABLE local_tenant_database_credential IS
    'Encrypted development-only credential adapter. Production provisioning uses an external secrets manager.';

ALTER TABLE practice_tenant_registry ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_tenant_registry FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_tenant_registry_scope ON practice_tenant_registry
    USING (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    )
    WITH CHECK (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    );

ALTER TABLE practice_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_membership FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_membership_scope ON practice_membership
    USING (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    )
    WITH CHECK (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    );

ALTER TABLE tenant_provisioning_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_provisioning_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_provisioning_event_scope ON tenant_provisioning_event
    USING (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    )
    WITH CHECK (
        COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true'
        OR practice_id = NULLIF(current_setting('epr.practice_id', TRUE), '')::UUID
    );

ALTER TABLE local_tenant_database_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE local_tenant_database_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY local_tenant_database_credential_scope ON local_tenant_database_credential
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true')
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');
