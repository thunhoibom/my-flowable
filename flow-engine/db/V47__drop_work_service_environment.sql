-- "Cấu hình môi trường" is replaced by the Flowable model: base URL and authorization travel in the service model
-- of each deployment; secrets, OAuth2 clients and tenant variables (V44-V46) hold what differs per environment.
DROP TABLE IF EXISTS work_service_environment;
