-- =====================================================================
--  V7 — audit hash-chain integrity (audit H-1)
--
--  The "immutable, verifiable journal" failed verifyChain() out of the box. The
--  monotonic created_at "+1 NANOsecond" guard in AuditService was silently truncated
--  by the timestamp(6) (microsecond) column, so rows written inside the same
--  microsecond (e.g. the seeder's burst of appends) collided on created_at and
--  verifyChain — which fell back to the RANDOM history_uid as the tiebreak —
--  reconstructed them out of hash-link order, reporting a false break.
--
--  Fix (three parts):
--    1. created_at -> timestamp(9): the nanosecond monotonic guard now persists.
--    2. chain_seq: a strictly-monotonic, clock-INDEPENDENT ordering key. verifyChain
--       walks by chain_seq, so its order can never again diverge from the hash links.
--    3. audit_chain_tip: a single-row anchor each append locks FOR UPDATE, so
--       concurrent appends serialize at the database (no fork) — replacing the
--       app-level `synchronized`, whose lock was released before the commit.
-- =====================================================================

-- 1. Nanosecond precision so the monotonic +1ns guard is stored, not truncated.
alter table histories alter column created_at timestamp(9) not null;

-- 2. Strictly-monotonic ordering key, independent of wall-clock precision.
alter table histories add column chain_seq bigint;

-- Backfill existing rows deterministically by (created_at, history_uid). One-time and
-- O(n^2) over a small journal; new rows take chain_seq from the tip pointer below.
update histories h set chain_seq = (
    select count(*) from histories h2
    where h2.created_at < h.created_at
       or (h2.created_at = h.created_at and h2.history_uid <= h.history_uid)
) where chain_seq is null;

-- 3. Single-row serialization anchor + chain tip pointer (last hash + last seq).
create table audit_chain_tip (
    id        bigint      not null,
    last_hash varchar(64) not null,
    last_seq  bigint      not null,
    primary key (id)
);

insert into audit_chain_tip (id, last_hash, last_seq) values (1, 'GENESIS', 0);

-- Continue the chain from the last existing entry (a no-op on a fresh database).
update audit_chain_tip set
    last_seq  = coalesce((select max(chain_seq) from histories), 0),
    last_hash = coalesce(
        (select entry_hash from histories
          where chain_seq = (select max(chain_seq) from histories)), 'GENESIS')
where id = 1;
