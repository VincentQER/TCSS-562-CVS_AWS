-- Run only after confirming this exact local user does not already exist.
-- No initial password and NO database grants: immediately set the password
-- interactively using mysqladmin as described in README.md, BEFORE granting access.
-- REQUIRE SSL prevents unencrypted connections; host is restricted to loopback.
CREATE USER 'tlq_load_it'@'127.0.0.1'
  IDENTIFIED WITH caching_sha2_password
  REQUIRE SSL;
