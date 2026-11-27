-- SEC-8: the first owner and owner-reset accounts must replace temporary credentials before normal API use.
alter table app_user
  add column must_change_password boolean not null default false;
