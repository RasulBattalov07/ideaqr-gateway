-- =====================================================================
--  V8 — architectural foundation from "Документ (22)" (CTO-approved subset)
--
--  Purpose (verbatim from Doc 22): lay the data-model foundation for future scaling
--  WITHOUT changing the existing core. Models + relationships + migrations + API stubs
--  only — NO engines (Policy/Consent engines, smart contracts, digital assets, etc.
--  remain Phase 2 and are intentionally absent: no tables, no endpoints, no stubs).
--
--  Adds:
--   * Organization made an EXPLICIT element of the pipeline -> requests.organization_uid
--   * Data Classification on the Object       -> registry_objects.data_level
--   * Event Source on every Event             -> events.source
--   * Consent / Policy / Relationship / Delegation foundation tables (polymorphic,
--     so new object types never need new join tables — Doc 22 universality rule).
-- =====================================================================

-- --- Organization in the golden pipeline -------------------------------------
-- Identifier -> Identity/Object -> Role -> ORGANIZATION -> Request -> Decision -> ...
alter table requests add column organization_uid uuid;

-- --- Data Classification (Doc 22) — derived default by category ---------------
alter table registry_objects add column data_level varchar(20);
update registry_objects set data_level =
    case category
        when 'MEDICAL'        then 'CONFIDENTIAL'
        when 'INFRASTRUCTURE' then 'RESTRICTED'
        else 'PUBLIC'
    end
where data_level is null;

-- --- Event Source (Doc 22) ---------------------------------------------------
alter table events add column source varchar(20);

-- --- Consent (Doc 22) --------------------------------------------------------
create table consents (
    consent_uid  uuid        not null,
    grantor_type varchar(20) not null,
    grantor_uid  uuid        not null,
    grantee_type varchar(20) not null,
    grantee_uid  uuid        not null,
    subject_type varchar(20) not null,
    subject_uid  uuid        not null,
    scope        varchar(60),
    status       varchar(20) not null,
    valid_until  timestamp(6),
    created_at   timestamp(6) not null,
    revoked_at   timestamp(6),
    primary key (consent_uid)
);

-- --- Policy catalog (Doc 22) — source of rules, read by a future engine -------
create table policies (
    policy_uid      uuid         not null,
    code            varchar(60)  not null,
    name            varchar(120) not null,
    description     varchar(400),
    object_category varchar(20),
    active          boolean      not null,
    created_at      timestamp(6) not null,
    primary key (policy_uid),
    constraint uk_policy_code unique (code)
);

-- Deterministic UUID literals (portable across H2/PostgreSQL; idempotent re-baselines).
insert into policies (policy_uid, code, name, description, object_category, active, created_at) values
    ('b0000000-0000-0000-0000-000000000001', 'PUBLIC_ACCESS',         'Публичный доступ',            'Общедоступные объекты: товары, услуги, эко.',      'RETAIL',         true, current_timestamp),
    ('b0000000-0000-0000-0000-000000000002', 'MEDICAL_ACCESS',        'Доступ к медицинским данным', 'Врач/фармацевт, уровень доверия и рабочее время.', 'MEDICAL',        true, current_timestamp),
    ('b0000000-0000-0000-0000-000000000003', 'PHARMACY_ACCESS',       'Доступ фармацевта',           'Доступ фармацевта к рецептам.',                    'MEDICAL',        true, current_timestamp),
    ('b0000000-0000-0000-0000-000000000004', 'INFRASTRUCTURE_ACCESS', 'Доступ к инфраструктуре',     'Инспектор/инженер, доверие и рабочее время.',      'INFRASTRUCTURE', true, current_timestamp),
    ('b0000000-0000-0000-0000-000000000005', 'SERVICE_ACCESS',        'Доступ к услугам',            'Бытовые услуги по заявке.',                        'GENERAL',        true, current_timestamp),
    ('b0000000-0000-0000-0000-000000000006', 'DOCUMENT_ACCESS',       'Доступ к документам',         'Документы и студенческие билеты.',                 'GENERAL',        true, current_timestamp),
    ('b0000000-0000-0000-0000-000000000007', 'ORGANIZATION_ACCESS',   'Корпоративный доступ',        'Доступ в рамках организации (cross-cutting).',     null,             true, current_timestamp);

-- --- Relationship (Doc 22) — universal directed edge -------------------------
create table relationships (
    relationship_uid  uuid        not null,
    from_type         varchar(20) not null,
    from_uid          uuid        not null,
    to_type           varchar(20) not null,
    to_uid            uuid        not null,
    relationship_type varchar(40) not null,
    status            varchar(20),
    created_at        timestamp(6) not null,
    primary key (relationship_uid)
);

-- --- Delegation (Doc 22) — universal authority hand-off ----------------------
create table delegations (
    delegation_uid uuid        not null,
    delegator_type varchar(20) not null,
    delegator_uid  uuid        not null,
    delegatee_type varchar(20) not null,
    delegatee_uid  uuid        not null,
    scope          varchar(60),
    status         varchar(20) not null,
    valid_until    timestamp(6),
    created_at     timestamp(6) not null,
    revoked_at     timestamp(6),
    primary key (delegation_uid)
);
