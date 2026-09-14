CREATE TABLE tenant_practice (
    practice_id UUID NOT NULL,
    practice_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT tenant_practice_pk PRIMARY KEY (practice_id),
    CONSTRAINT tenant_practice_code_check
        CHECK (practice_code ~ '^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$')
);

CREATE UNIQUE INDEX tenant_practice_singleton ON tenant_practice ((TRUE));

CREATE TABLE tenant_administrator_reference (
    practice_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT tenant_administrator_reference_pk PRIMARY KEY (practice_id, user_id),
    CONSTRAINT tenant_administrator_reference_practice_fk
        FOREIGN KEY (practice_id) REFERENCES tenant_practice (practice_id)
);

COMMENT ON TABLE tenant_practice IS
    'Singleton tenant identity used to fail closed if a database route points at the wrong Practice.';
COMMENT ON TABLE tenant_administrator_reference IS
    'Tenant-side reference only; current membership authorization remains authoritative in the control plane.';
