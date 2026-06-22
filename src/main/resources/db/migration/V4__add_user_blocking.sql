-- =====================================================================
--  IDEAQR Digital Gateway — V4: user blocking metadata
--
--  The boolean `blocked` flag already exists (V1 baseline) and already gates
--  login (Spring Security account-locked) and every authenticated request
--  (AuthSupport). This migration enriches it with audit metadata kept on the
--  row itself, so the admin User Management table can show WHY and WHEN an
--  account was blocked — not just that it is.
--
--  Portable ANSI syntax (H2 + PostgreSQL); both columns are nullable
--  (populated only while an account is blocked).
-- =====================================================================

alter table users add column blocked_at     timestamp(6);
alter table users add column blocked_reason varchar(300);
