-- =====================================================================
--  IDEAQR Digital Gateway — Flyway baseline (V1)
--
--  Full initial schema, generated from the JPA entity model (Hibernate
--  schema export) so it matches exactly what `ddl-auto=validate` expects —
--  no field is lost. Written in the portable SQL subset shared by H2 and
--  PostgreSQL (uuid / varchar / integer / timestamp / boolean / check),
--  so a single baseline validates on both. The only adjustment vs. the raw
--  export is data_json (a @Lob → CLOB on H2 / OID on PG) which the entity now
--  maps to a portable varchar(16000).
--
--  Relationships are intentionally stored as flat UUID columns (Stage 2
--  architecture) — the only foreign keys are the two element-collection
--  side tables, which reference identities.
-- =====================================================================

create table assignments (
    assigned_at timestamp(6) not null,
    assignment_uid uuid not null,
    identity_uid uuid not null,
    qr_uid uuid not null,
    assignment_role varchar(40),
    primary key (assignment_uid)
);

create table complaints (
    created_at timestamp(6) not null,
    complaint_uid uuid not null,
    identity_uid uuid not null,
    interaction_uid uuid not null,
    status varchar(20) not null check (status in ('NEW','IN_PROGRESS','RESOLVED','REJECTED')),
    category varchar(60) not null,
    subject varchar(200) not null,
    description varchar(1000),
    primary key (complaint_uid)
);

create table decisions (
    created_at timestamp(6) not null,
    decision_uid uuid not null,
    identity_uid uuid not null,
    request_uid uuid not null,
    outcome varchar(20) not null check (outcome in ('APPROVED','REJECTED','REVIEW')),
    risk_level varchar(20),
    reason_code varchar(60),
    reason varchar(500),
    primary key (decision_uid)
);

create table events (
    created_at timestamp(6) not null,
    event_uid uuid not null,
    identity_uid uuid,
    interaction_uid uuid,
    event_type varchar(40) not null check (event_type in ('IDENTITY_CREATED','IDENTITY_VERIFIED','REQUEST_CREATED','DECISION_APPROVED','DECISION_REJECTED','DECISION_REVIEW','INTERACTION_CREATED','QR_VIEWED','PROFILE_OPENED','ACCESS_REQUESTED','ACCESS_CONFIRMED','SOS_CREATED','ASSIGNMENT_CREATED','WORKING_MODE_ACTIVATED','WORKING_MODE_DEACTIVATED','COMPLAINT_CREATED','GUEST_MERGED','SERVICE_STARTED','SERVICE_COMPLETED','OBJECT_TRANSFERRED','OBJECT_MODIFIED','OBJECT_ARCHIVED','USER_BLOCKED','USER_UNBLOCKED','USER_ROLE_CHANGED','USER_PASSWORD_RESET')),
    object_uid varchar(120),
    summary varchar(300),
    primary key (event_uid)
);

create table histories (
    created_at timestamp(6) not null,
    decision_uid uuid,
    history_uid uuid not null,
    identity_uid uuid not null,
    interaction_uid uuid,
    request_uid uuid,
    event_type varchar(30) not null check (event_type in ('ACCESS_GRANTED','ACCESS_DENIED','ACCESS_REVIEW','QR_CREATED','ISSUE_REPORTED','IDENTITY_CREATED','IDENTITY_VERIFIED','USER_REGISTERED','WORKING_MODE_ACTIVATED','WORKING_MODE_DEACTIVATED','SOS_CREATED','GUEST_CREATED','GUEST_MERGED','NOTIFICATION_CREATED','PROFILE_ACCESS_REQUESTED','PROFILE_ACCESS_CONFIRMED','PROFILE_ACCESS_REJECTED','COMPLAINT_CREATED','OBJECT_MODIFIED','OBJECT_ARCHIVED','OBJECT_TRANSFERRED','USER_BLOCKED','USER_UNBLOCKED','USER_ROLE_CHANGED','USER_PASSWORD_RESET','USER_PASSWORD_CHANGED')),
    entry_hash varchar(64),
    prev_hash varchar(64),
    object_uid varchar(120),
    description varchar(500),
    primary key (history_uid)
);

create table identities (
    trust_level integer not null,
    trust_score integer,
    created_at timestamp(6) not null,
    identity_uid uuid not null,
    primary_qr_uid uuid,
    identity_type varchar(20) not null check (identity_type in ('PRIMARY','GUEST')),
    risk_score varchar(20),
    status varchar(20) not null check (status in ('ACTIVE','SUSPENDED','PENDING')),
    merge_token_hash varchar(64),
    primary key (identity_uid)
);

create table identity_linked_guests (
    guest_identity_uid uuid,
    identity_uid uuid not null
);

create table identity_roles (
    identity_uid uuid not null,
    role varchar(30) check (role in ('CITIZEN','DOCTOR','ENGINEER','INSPECTOR','RETAIL_ADMIN','OBJECT_OWNER','ORG_STAFF','ADMIN','PHARMACIST','SELLER','SERVICE_OPERATOR'))
);

create table interactions (
    created_at timestamp(6) not null,
    identity_uid uuid not null,
    interaction_uid uuid not null,
    request_uid uuid not null,
    target_identity_uid uuid,
    status varchar(20) check (status in ('PENDING','CONFIRMED','REJECTED')),
    interaction_type varchar(40) not null,
    object_uid varchar(120),
    detail varchar(400),
    primary key (interaction_uid)
);

create table notifications (
    created_at timestamp(6) not null,
    identity_uid uuid not null,
    notification_uid uuid not null,
    status varchar(20) not null check (status in ('NEW','READ','ARCHIVED')),
    notification_type varchar(40) not null,
    title varchar(240) not null,
    primary key (notification_uid)
);

create table organization_memberships (
    created_at timestamp(6) not null,
    identity_uid uuid not null,
    membership_uid uuid not null,
    organization_uid uuid not null,
    status varchar(20),
    work_role varchar(40),
    primary key (membership_uid)
);

create table organizations (
    created_at timestamp(6) not null,
    organization_uid uuid not null,
    status varchar(20),
    type varchar(40),
    name varchar(160) not null,
    primary key (organization_uid)
);

create table platform_modules (
    created_at timestamp(6) not null,
    module_uid uuid not null,
    status varchar(20) not null check (status in ('ACTIVE','DISABLED')),
    code varchar(40) not null,
    name varchar(120) not null,
    description varchar(400),
    primary key (module_uid),
    constraint uk_module_code unique (code)
);

create table qrs (
    created_at timestamp(6) not null,
    owner_identity_uid uuid not null,
    qr_uid uuid not null,
    qr_type varchar(20) not null check (qr_type in ('PRIMARY','OBJECT')),
    status varchar(20) not null check (status in ('PENDING','ACTIVE','REVOKED')),
    qr_value varchar(200) not null,
    primary key (qr_uid)
);

create table registry_objects (
    trust_score integer,
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    created_by_identity_uid uuid not null,
    qr_uid uuid,
    registry_uid uuid not null,
    category varchar(20) not null check (category in ('MEDICAL','RETAIL','ECO','INFRASTRUCTURE','GENERAL','UNKNOWN')),
    status varchar(20) not null check (status in ('CREATED','ACTIVE','MODIFIED','ARCHIVED')),
    object_uid varchar(120) not null,
    display_name varchar(200) not null,
    data_json varchar(16000) not null,
    primary key (registry_uid),
    constraint uk_registry_object_uid unique (object_uid)
);

create table requests (
    created_at timestamp(6) not null,
    identity_uid uuid not null,
    request_uid uuid not null,
    status varchar(20) not null check (status in ('PENDING','PROCESSED','FAILED','NEW','REVIEW','APPROVED','REJECTED','COMPLETED')),
    request_type varchar(30) not null check (request_type in ('ACCESS','QR_CREATION','REPORT_ISSUE','SOS','WORKING_MODE','OBJECT_LIFECYCLE')),
    object_uid varchar(120),
    primary key (request_uid)
);

create table user_sessions (
    started_at timestamp(6) not null,
    updated_at timestamp(6),
    active_organization_uid uuid,
    identity_uid uuid not null,
    session_uid uuid not null,
    mode varchar(20) not null check (mode in ('PERSONAL','WORKING')),
    status varchar(20),
    active_role varchar(40),
    primary key (session_uid),
    constraint uk_session_identity unique (identity_uid)
);

create table users (
    blocked boolean not null,
    is_admin boolean not null,
    must_change_password boolean not null,
    created_at timestamp(6) not null,
    identity_uid uuid not null,
    user_uid uuid not null,
    employment_status varchar(20) not null check (employment_status in ('EMPLOYED','UNEMPLOYED')),
    profession varchar(40) not null,
    username varchar(60) not null,
    first_name varchar(80) not null,
    last_name varchar(80) not null,
    password_hash varchar(100) not null,
    primary key (user_uid),
    constraint uk_users_username unique (username)
);

create table workflows (
    created_at timestamp(6) not null,
    request_uid uuid not null,
    workflow_uid uuid not null,
    status varchar(30),
    workflow_type varchar(40),
    primary key (workflow_uid)
);

-- The only foreign keys: the @ElementCollection side tables → identities.
alter table identity_linked_guests
    add constraint fk_identity_linked_guests_identity
    foreign key (identity_uid) references identities (identity_uid);

alter table identity_roles
    add constraint fk_identity_roles_identity
    foreign key (identity_uid) references identities (identity_uid);
