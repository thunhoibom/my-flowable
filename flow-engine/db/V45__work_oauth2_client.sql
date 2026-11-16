-- OAuth2 clients of this Flow Engine, like Flowable Hub "OAuth2 Clients". Service models reference a client by
-- registration key; Flow Engine fetches the access token at call time (client credentials, token URI given
-- directly, no issuer discovery). Only the client secret is encrypted.
CREATE TABLE work_oauth2_client (
    tenant_id            VARCHAR(64)  NOT NULL,
    registration_key     VARCHAR(128) NOT NULL,
    client_id            VARCHAR(255) NOT NULL,
    client_secret_cipher TEXT         NOT NULL,
    token_uri            TEXT         NOT NULL,
    scopes               TEXT,
    grant_type           VARCHAR(32)  NOT NULL,
    updated_by           VARCHAR(255) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT work_oauth2_client_pkey PRIMARY KEY (tenant_id, registration_key),
    CONSTRAINT work_oauth2_client_grant_type_check CHECK (grant_type IN ('CLIENT_CREDENTIALS'))
);

ALTER TABLE work_oauth2_client ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_oauth2_client FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_work_oauth2_client ON work_oauth2_client
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
