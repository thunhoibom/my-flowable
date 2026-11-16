-- Secrets of this Flow Engine, like Flowable Hub "Secrets": a name and an encrypted value, per tenant.
-- Service models reference a secret by name; the value is never read back. Changing a secret means deleting
-- and creating it again. Encrypted with workflow.service.secrets.master-key-base64.
CREATE TABLE work_secret (
    tenant_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    value_cipher TEXT         NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT work_secret_pkey PRIMARY KEY (tenant_id, name)
);

ALTER TABLE work_secret ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_secret FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_work_secret ON work_secret
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
