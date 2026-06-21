-- =====================================================================
--  IDEAQR Digital Gateway — V2: referential integrity (audit 3.6)
--
--  Adds real FOREIGN KEY constraints so the database — not application
--  convention — guarantees relationships and makes "dangling" rows
--  impossible. Portable ANSI syntax (H2 + PostgreSQL).
--
--  Notes:
--   * Four of these back the new JPA @ManyToOne associations
--     (users→identity, qrs→owner identity, assignments→qr/identity);
--     the rest enforce integrity on the governance-pipeline tables that
--     intentionally remain flat UUID columns (an append-only event store,
--     not managed aggregates).
--   * object_uid is deliberately NOT a foreign key: it can reference an
--     external/demo registry code that does not live in registry_objects.
--   * Nullable FK columns permit NULL (no reference) and are unaffected.
--   * identity_roles and identity_linked_guests(identity_uid) already had
--     FKs from V1.
-- =====================================================================

-- --- JPA association-backed foreign keys ------------------------------
alter table users
    add constraint fk_users_identity
    foreign key (identity_uid) references identities (identity_uid);

alter table qrs
    add constraint fk_qrs_owner_identity
    foreign key (owner_identity_uid) references identities (identity_uid);

alter table assignments
    add constraint fk_assignments_qr
    foreign key (qr_uid) references qrs (qr_uid);

alter table assignments
    add constraint fk_assignments_identity
    foreign key (identity_uid) references identities (identity_uid);

-- --- Identity ↔ primary QR --------------------------------------------
alter table identities
    add constraint fk_identities_primary_qr
    foreign key (primary_qr_uid) references qrs (qr_uid);

-- --- Governance pipeline: Request → Decision → Interaction → History ---
alter table requests
    add constraint fk_requests_identity
    foreign key (identity_uid) references identities (identity_uid);

alter table decisions
    add constraint fk_decisions_request
    foreign key (request_uid) references requests (request_uid);
alter table decisions
    add constraint fk_decisions_identity
    foreign key (identity_uid) references identities (identity_uid);

alter table interactions
    add constraint fk_interactions_identity
    foreign key (identity_uid) references identities (identity_uid);
alter table interactions
    add constraint fk_interactions_request
    foreign key (request_uid) references requests (request_uid);
alter table interactions
    add constraint fk_interactions_target_identity
    foreign key (target_identity_uid) references identities (identity_uid);

alter table histories
    add constraint fk_histories_identity
    foreign key (identity_uid) references identities (identity_uid);
alter table histories
    add constraint fk_histories_request
    foreign key (request_uid) references requests (request_uid);
alter table histories
    add constraint fk_histories_decision
    foreign key (decision_uid) references decisions (decision_uid);
alter table histories
    add constraint fk_histories_interaction
    foreign key (interaction_uid) references interactions (interaction_uid);

-- --- Notifications / events / complaints -------------------------------
alter table notifications
    add constraint fk_notifications_identity
    foreign key (identity_uid) references identities (identity_uid);

alter table events
    add constraint fk_events_identity
    foreign key (identity_uid) references identities (identity_uid);
alter table events
    add constraint fk_events_interaction
    foreign key (interaction_uid) references interactions (interaction_uid);

alter table complaints
    add constraint fk_complaints_identity
    foreign key (identity_uid) references identities (identity_uid);
alter table complaints
    add constraint fk_complaints_interaction
    foreign key (interaction_uid) references interactions (interaction_uid);

-- --- Organizations / memberships / sessions ----------------------------
alter table organization_memberships
    add constraint fk_org_memberships_identity
    foreign key (identity_uid) references identities (identity_uid);
alter table organization_memberships
    add constraint fk_org_memberships_organization
    foreign key (organization_uid) references organizations (organization_uid);

alter table user_sessions
    add constraint fk_user_sessions_identity
    foreign key (identity_uid) references identities (identity_uid);
alter table user_sessions
    add constraint fk_user_sessions_active_org
    foreign key (active_organization_uid) references organizations (organization_uid);

-- --- Registry objects / workflows / guest alias ------------------------
alter table registry_objects
    add constraint fk_registry_objects_created_by
    foreign key (created_by_identity_uid) references identities (identity_uid);
alter table registry_objects
    add constraint fk_registry_objects_qr
    foreign key (qr_uid) references qrs (qr_uid);

alter table workflows
    add constraint fk_workflows_request
    foreign key (request_uid) references requests (request_uid);

alter table identity_linked_guests
    add constraint fk_identity_linked_guests_guest
    foreign key (guest_identity_uid) references identities (identity_uid);
