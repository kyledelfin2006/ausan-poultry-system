-- Pre-UI audit hardening.

-- PERF-4: serve the default delivery list order (newest first, no status filter) and the branch filter.
create index ix_delivery_started_id on delivery (started_at desc, id desc);
create index ix_delivery_branch_started on delivery (from_branch_id, started_at desc);

-- SEC-6: usernames are unique ignoring case. Skipped (with a warning) if old data already holds look-alike names, so
-- the migration can never block a deployment; the application still normalises new usernames either way.
do $$
begin
  if exists (select 1 from app_user group by lower(username) having count(*) > 1) then
    raise warning 'app_user holds usernames that differ only by case; ux_app_user_username_lower not created';
  else
    create unique index ux_app_user_username_lower on app_user (lower(username));
  end if;
end $$;
