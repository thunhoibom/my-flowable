Copies of Flow Engine migrations that create the tables Flow Admin BE reuses (catalog, drafts, releases).
Only tests run them, to build those tables in a throwaway Postgres; Flow Admin BE never migrates them.
