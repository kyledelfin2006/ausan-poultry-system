create table branch (
  id     bigint generated always as identity primary key,
  name   varchar(80) not null unique,
  active boolean not null default true
);

create table product (
  id            bigint generated always as identity primary key,
  name          varchar(120) not null unique,
  unit          varchar(20)  not null,
  default_price numeric(12,2) check (default_price >= 0),   -- PHP; suggested selling price, editable per delivery
  active        boolean not null default true
);

-- Placeholder branch names: the owner renames them via PATCH /api/branches/{id}.
insert into branch (name) values ('Branch 1'), ('Branch 2');

insert into product (name, unit, default_price) values
  ('Cage',      'pc',   null),
  ('Chicken',   'head', 380.00),
  ('Nipple',    'pc',   20.00),
  ('Water jug', 'pc',   null);
