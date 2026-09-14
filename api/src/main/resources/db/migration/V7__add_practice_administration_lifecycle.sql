ALTER TABLE practice_tenant_registry
    ADD COLUMN service_status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
    ADD COLUMN suspended_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN suspension_reason VARCHAR(500),
    ADD COLUMN suspended_by_user_id UUID,
    ADD CONSTRAINT practice_tenant_registry_service_status_check
        CHECK (service_status IN ('ENABLED', 'SUSPENDED')),
    ADD CONSTRAINT practice_tenant_registry_suspended_by_fk
        FOREIGN KEY (suspended_by_user_id) REFERENCES platform_user_account (id),
    ADD CONSTRAINT practice_tenant_registry_service_lifecycle_check
        CHECK (
            (service_status = 'ENABLED'
                AND suspended_at IS NULL
                AND suspension_reason IS NULL
                AND suspended_by_user_id IS NULL)
            OR (service_status = 'SUSPENDED'
                AND provisioning_status = 'ACTIVE'
                AND suspended_at IS NOT NULL
                AND suspension_reason IS NOT NULL
                AND suspended_by_user_id IS NOT NULL)
        );

CREATE TABLE practice_administration_event (
    id UUID NOT NULL,
    practice_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_user_id UUID NOT NULL,
    reason VARCHAR(500),
    previous_value VARCHAR(500),
    next_value VARCHAR(500),
    idempotency_key UUID NOT NULL,
    resulting_version BIGINT NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT practice_administration_event_pk PRIMARY KEY (id),
    CONSTRAINT practice_administration_event_idempotency_unique UNIQUE (idempotency_key),
    CONSTRAINT practice_administration_event_practice_fk
        FOREIGN KEY (practice_id) REFERENCES practice_tenant_registry (practice_id),
    CONSTRAINT practice_administration_event_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES platform_user_account (id),
    CONSTRAINT practice_administration_event_type_check
        CHECK (event_type IN ('DISPLAY_NAME_UPDATED', 'PRACTICE_SUSPENDED', 'PRACTICE_REACTIVATED')),
    CONSTRAINT practice_administration_event_version_check CHECK (resulting_version > 0)
);

CREATE INDEX practice_tenant_registry_updated_idx
    ON practice_tenant_registry (updated_at DESC, practice_id);
CREATE INDEX practice_administration_event_practice_time_idx
    ON practice_administration_event (practice_id, occurred_at DESC);

COMMENT ON COLUMN practice_tenant_registry.service_status IS
    'Control-plane service access state, independent from provisioning health.';
COMMENT ON TABLE practice_administration_event IS
    'Append-only audited Practice metadata and service-access changes.';

ALTER TABLE practice_administration_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_administration_event FORCE ROW LEVEL SECURITY;
CREATE POLICY practice_administration_event_scope ON practice_administration_event
    USING (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true')
    WITH CHECK (COALESCE(current_setting('epr.platform_operations', TRUE), '') = 'true');
