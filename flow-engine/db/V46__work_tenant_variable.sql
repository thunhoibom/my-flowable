-- Tenant variables of this Flow Engine, like Flowable Hub "Tenant Variables": key/value settings read at runtime
-- through ${tenant.<name>}, e.g. service endpoints that differ per environment. A protected variable keeps its
-- value only encrypted (value_cipher) and can never become unprotected.
CREATE TABLE work_tenant_variable (
    tenant_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    value        TEXT,
    value_cipher TEXT,
    protected    BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT work_tenant_variable_pkey PRIMARY KEY (tenant_id, name),
    CONSTRAINT work_tenant_variable_value_check CHECK (
        (protected AND value IS NULL AND value_cipher IS NOT NULL)
        OR (NOT protected AND value_cipher IS NULL))
);

ALTER TABLE work_tenant_variable ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_tenant_variable FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_work_tenant_variable ON work_tenant_variable
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
