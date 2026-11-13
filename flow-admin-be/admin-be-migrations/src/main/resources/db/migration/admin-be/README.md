Flyway migrations for the tables owned by Flow Admin BE (schema `public`).

- History table: `flyway_schema_history_admin_be` (separate from Flow Engine's `flyway_schema_history`).
- Numbering starts at V1. `baseline-on-migrate` is on because the reused tables
  (`workflow_process_draft`, `service_definition`, ...) were created by Flow Engine V1–V41.
- Only touch tables owned by Flow Admin BE (see the Data section of the architecture spec).
