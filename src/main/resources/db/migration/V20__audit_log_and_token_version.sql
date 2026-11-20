-- V20: audit trail of master-data changes, and a per-user token version for sign-out / password-change revocation.

-- Every access token carries the user's token_version at login (claim "ver"). A request is refused when the claim no
-- longer matches this column, so logout and password changes end all of that user's sessions immediately. Tokens issued
-- before this migration have no claim; they are read as version 0, which matches the default, so nobody is signed out.
alter table app_user add column token_version integer not null default 0;

-- Append-only record of WHO changed users, products, packages and branches. The module owns this table; other modules
-- write to it only through AuditApi. There is deliberately no foreign key to app_user: the username is copied into the
-- row, so the trail stays readable even if users are ever removed, and audit does not depend on the identity tables.
create table audit_log (
  id             bigint generated always as identity primary key,
  -- When the change was committed (UTC instant).
  occurred_at    timestamptz   not null,
  -- The user who made the change; null when the system itself did it (for example the first-owner bootstrap).
  actor_user_id  bigint,
  -- The actor's username at that moment (a copy, see above); 'system' when actor_user_id is null.
  actor_username varchar(50)   not null,
  -- What happened: CREATED, UPDATED, PASSWORD_RESET, PASSWORD_CHANGED, SIGNED_OUT.
  action         varchar(30)   not null,
  -- Which kind of record: USER, PRODUCT, BRANCH, PACKAGE.
  entity_type    varchar(30)   not null,
  -- The record's id (a number in the owning module's table); there is no foreign key across modules.
  entity_id      bigint        not null,
  -- Human-readable description, such as "price: 70000.00 -> 75000.00; active: true -> false". Never holds secrets.
  summary        varchar(1000) not null,
  -- The X-Request-Id of the HTTP request, to find the matching log lines; null outside a request.
  request_id     varchar(64)
);

-- Typical questions: "what happened recently", "what happened to this record", "what did this person do".
create index audit_log_occurred_idx on audit_log (occurred_at desc, id desc);
create index audit_log_entity_idx   on audit_log (entity_type, entity_id);
create index audit_log_actor_idx    on audit_log (actor_user_id);

-- Append-only is enforced by the database itself, not just by application code: any UPDATE or DELETE is refused.
create function audit_log_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'audit_log is append-only';
end;
$$;

create trigger audit_log_no_change before update or delete on audit_log
  for each row execute function audit_log_immutable();

-- TRUNCATE skips row triggers, so it needs its own statement-level trigger to be refused as well.
create trigger audit_log_no_truncate before truncate on audit_log
  for each statement execute function audit_log_immutable();
