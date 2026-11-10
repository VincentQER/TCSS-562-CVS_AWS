-- Run only AFTER mysqladmin's interactive password setup succeeds.
-- Only the two new local fixture tables. No database-wide or global grants.
GRANT INSERT, SELECT ON `tlq_load_it`.`payments_raw`
  TO 'tlq_load_it'@'127.0.0.1';
GRANT INSERT, SELECT ON `tlq_load_it`.`payments_rollback`
  TO 'tlq_load_it'@'127.0.0.1';

SHOW GRANTS FOR 'tlq_load_it'@'127.0.0.1';
