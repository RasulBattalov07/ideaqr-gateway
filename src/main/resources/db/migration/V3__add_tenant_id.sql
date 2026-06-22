-- =====================================================================
--  IDEAQR Digital Gateway — V3: multitenancy discriminator (audit 5.3)
--
--  Adds a tenant_id column to the customer-data tables so different
--  organisations (e.g. two hospitals or two retailers) are hard-isolated.
--  Hibernate's tenantFilter appends `where tenant_id = ?` to every read of
--  these entities, and TenantListener stamps it on every insert.
--
--  Portable ANSI syntax (H2 + PostgreSQL). Pre-existing rows are backfilled
--  to the public tenant (00000000-…-0) so nothing becomes unreachable.
-- =====================================================================

alter table users            add column tenant_id uuid;
alter table identities       add column tenant_id uuid;
alter table qrs              add column tenant_id uuid;
alter table registry_objects add column tenant_id uuid;
alter table requests         add column tenant_id uuid;
alter table histories        add column tenant_id uuid;

-- Backfill any rows that existed before this migration to the public tenant.
update users            set tenant_id = '00000000-0000-0000-0000-000000000000' where tenant_id is null;
update identities       set tenant_id = '00000000-0000-0000-0000-000000000000' where tenant_id is null;
update qrs              set tenant_id = '00000000-0000-0000-0000-000000000000' where tenant_id is null;
update registry_objects set tenant_id = '00000000-0000-0000-0000-000000000000' where tenant_id is null;
update requests         set tenant_id = '00000000-0000-0000-0000-000000000000' where tenant_id is null;
update histories        set tenant_id = '00000000-0000-0000-0000-000000000000' where tenant_id is null;

-- Indexes so the per-tenant filter stays cheap.
create index ix_users_tenant            on users(tenant_id);
create index ix_identities_tenant       on identities(tenant_id);
create index ix_qrs_tenant              on qrs(tenant_id);
create index ix_registry_objects_tenant on registry_objects(tenant_id);
create index ix_requests_tenant         on requests(tenant_id);
create index ix_histories_tenant        on histories(tenant_id);
