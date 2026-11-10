-- Read-only preflight. No authentication strings, passwords or unrelated database data.
SELECT VERSION() AS server_version, @@version_comment AS server_distribution,
       CURRENT_USER() AS current_account, @@port AS server_port;
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
 WHERE SCHEMA_NAME = 'tlq_load_it';
SELECT User, Host FROM mysql.user WHERE User = 'tlq_load_it';
-- If the database name OR any matching username already exists, STOP and report.
-- Do not run the creation scripts, overwrite accounts, or grant access to existing data.
