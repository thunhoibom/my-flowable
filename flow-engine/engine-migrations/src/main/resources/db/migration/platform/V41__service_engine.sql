-- Service engine, modelled on the FLW_SE_* tables of Flowable Enterprise and the column types of
-- the open source FLW_EVENT_* tables. A process deployment that carries <serviceKey>.service resources
-- gets one child service deployment whose parent_deployment_id_ is the process deployment id.
-- Like the ACT_* and FLW_EVENT_* tables there is no row level security. Queries filter on tenant_id_.

CREATE TABLE flw_se_deployment (
    id_                   VARCHAR(255) NOT NULL,
    name_                 VARCHAR(255),
    key_                  VARCHAR(255),
    category_             VARCHAR(255),
    deploy_time_          TIMESTAMP(3) WITHOUT TIME ZONE,
    tenant_id_            VARCHAR(255) DEFAULT '',
    parent_deployment_id_ VARCHAR(255),
    CONSTRAINT flw_se_deployment_pkey PRIMARY KEY (id_)
);

CREATE INDEX flw_idx_se_depl_parent ON flw_se_deployment (parent_deployment_id_);

CREATE TABLE flw_se_deployment_resource (
    id_             VARCHAR(255) NOT NULL,
    name_           VARCHAR(255),
    deployment_id_  VARCHAR(255),
    resource_bytes_ BYTEA,
    CONSTRAINT flw_se_deployment_resource_pkey PRIMARY KEY (id_)
);

ALTER TABLE flw_se_deployment_resource
    ADD CONSTRAINT flw_fk_se_rsrc_dpl FOREIGN KEY (deployment_id_) REFERENCES flw_se_deployment (id_);

CREATE INDEX flw_idx_se_rsrc_dpl ON flw_se_deployment_resource (deployment_id_);

CREATE TABLE flw_se_definition (
    id_            VARCHAR(255) NOT NULL,
    rev_           INTEGER      NOT NULL,
    key_           VARCHAR(255),
    deployment_id_ VARCHAR(255) NOT NULL,
    tenant_id_     VARCHAR(255) DEFAULT '',
    name_          VARCHAR(255),
    version_       INTEGER,
    resource_name_ VARCHAR(255),
    CONSTRAINT flw_se_definition_pkey PRIMARY KEY (id_)
);

ALTER TABLE flw_se_definition
    ADD CONSTRAINT flw_fk_se_def_dpl FOREIGN KEY (deployment_id_) REFERENCES flw_se_deployment (id_);

CREATE UNIQUE INDEX flw_idx_se_def_uniq ON flw_se_definition (key_, version_, tenant_id_);

CREATE INDEX flw_idx_se_def_dpl ON flw_se_definition (deployment_id_);

-- A waiting service task resolves its service definition in the same parent deployment on callback.
ALTER TABLE service_callback_wait
    ADD COLUMN parent_deployment_id VARCHAR(255),
    ADD COLUMN tenant_id VARCHAR(255);
