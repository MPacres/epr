CREATE TABLE platform_user_account (
    id UUID NOT NULL,
    username VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT platform_user_account_pk PRIMARY KEY (id),
    CONSTRAINT platform_user_account_username_unique UNIQUE (username),
    CONSTRAINT platform_user_account_role_check CHECK (role IN ('SUPERADMIN', 'PROVIDER_SUPPORT', 'CLINICIAN'))
);

COMMENT ON TABLE platform_user_account IS
    'Global workforce identities. Practice permissions remain separate and must be authorized per request.';

