-- Flow Admin BE calls the Deployment API with a service token (architecture spec, "API của Flow Engine").
-- The shared secret is read from the environment variable FLOW_ADMIN_BE_SERVICE_SECRET of Flow Engine; while it is
-- not set, tokens of this client cannot be checked and every call is refused.
INSERT INTO workflow_service_client
    (client_code, client_name, secret_key_ref, secret_version, allowed_tenant_codes, allowed_paths, enabled,
     description)
VALUES
    ('flow-admin-be', 'Flow Admin BE', 'env:FLOW_ADMIN_BE_SERVICE_SECRET', 1, '*', '/api/deployment/**', 1,
     'Design and Hub backend: deploys processes, services and events')
ON CONFLICT (client_code) DO NOTHING;
