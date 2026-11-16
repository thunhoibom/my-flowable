-- Environment settings of a service on this Flow Engine: base URL and credentials.
-- The design of a service travels with each process deployment (flw_se_*). What depends on the
-- environment (URL, secrets) lives here, is edited by operators (Admin API, later Hub) and takes effect
-- on the next call without republishing anything.
-- auth_config holds the non-secret part as JSON. secret_cipher holds every secret, encrypted with the
-- service master key (workflow.service.secrets.master-key-base64), as one JSON document.
CREATE TABLE work_service_environment (
    tenant_id     VARCHAR(64)  NOT NULL,
    service_key   VARCHAR(128) NOT NULL,
    base_url      TEXT,
    auth_config   TEXT         NOT NULL,
    secret_cipher TEXT,
    updated_by    VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT work_service_environment_pkey PRIMARY KEY (tenant_id, service_key)
);

ALTER TABLE work_service_environment ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_service_environment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_work_service_environment ON work_service_environment
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
