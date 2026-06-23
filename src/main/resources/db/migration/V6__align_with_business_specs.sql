-- =====================================================================
--  V6 — align with business specs: explicit Object ownership
--
--  The FINAL ТЗ (ДокумР) models every Object with an `ownerIdentityId` that is
--  distinct from its creator. This is what makes the customer's transfer scenario
--  (Расулу — продажа автомобиля) work *without* re-minting the object: ownership
--  changes, but the object's UUID, QR and full History (chain of custody) are
--  preserved. Notably the FINAL ТЗ also prescribes exactly this in its
--  "ЧТО НЕ РЕАЛИЗОВЫВАТЬ" section — "Для объектов использовать: ownerIdentityId" —
--  i.e. plain ownership instead of a heavier ownership/token/Trust-Score model.
--
--  Scope is deliberately one column: the OBJECT_TRANSFERRED event/history types
--  already exist in the V1 baseline check constraints, so no constraint changes
--  are needed and the migration stays a pure additive ALTER.
--
--  Portable across H2 and PostgreSQL (uuid). Existing rows are backfilled so the
--  owner equals the original creator — a no-op for everything created so far.
-- =====================================================================

alter table registry_objects
    add column owner_identity_uid uuid;

update registry_objects
   set owner_identity_uid = created_by_identity_uid
 where owner_identity_uid is null;
