create table app_user (
  id            bigint generated always as identity primary key,
  username      varchar(50)  not null unique,
  password_hash varchar(100) not null,
  display_name  varchar(100) not null,
  role          varchar(20)  not null check (role in ('OWNER','ASSISTANT')),
  active        boolean      not null default true,
  created_at    timestamptz  not null
);
