-- New inventory items. Feed is counted in bags.
insert into product (name, unit) values ('Feeders', 'pc'), ('Feed', 'bag');

-- Selling is done by package, so per-product suggested prices are no longer meaningful.
update product set default_price = null;

create table sales_package (
  id     bigint generated always as identity primary key,
  sku    varchar(40)   not null unique,
  name   varchar(120)  not null unique,
  price  numeric(14,2) not null check (price > 0),     -- PHP
  active boolean       not null default true
);

create table package_item (
  package_id bigint not null references sales_package(id),
  product_id bigint not null references product(id),
  quantity   bigint not null check (quantity > 0),
  primary key (package_id, product_id)
);

insert into sales_package (sku, name, price) values
  ('PKG-RTL-SML', 'Package Small',      70000.00),
  ('PKG-RTL-MED', 'Package Medium',    160000.00),
  ('PKG-RTL-COM', 'Package Commercial', 700000.00);

insert into package_item (package_id, product_id, quantity)
select p.id, pr.id, v.qty
  from (values
    ('PKG-RTL-SML', 'Chicken',  24), ('PKG-RTL-SML', 'Cage',  2), ('PKG-RTL-SML', 'Nipple',   8), ('PKG-RTL-SML', 'Feeders',  4), ('PKG-RTL-SML', 'Feed',  6),
    ('PKG-RTL-MED', 'Chicken',  96), ('PKG-RTL-MED', 'Cage',  4), ('PKG-RTL-MED', 'Nipple',  24), ('PKG-RTL-MED', 'Feeders',  8), ('PKG-RTL-MED', 'Feed', 16),
    ('PKG-RTL-COM', 'Chicken', 480), ('PKG-RTL-COM', 'Cage', 10), ('PKG-RTL-COM', 'Nipple', 120), ('PKG-RTL-COM', 'Feeders', 40), ('PKG-RTL-COM', 'Feed', 80)
  ) as v(sku, product_name, qty)
  join sales_package p on p.sku = v.sku
  join product pr on pr.name = v.product_name;

create sequence delivery_code_seq;

create table delivery (
  id             bigint generated always as identity primary key,
  code           varchar(20)   not null unique,
  status         varchar(12)   not null check (status in ('ACTIVE','DONE','CANCELLED')),
  customer_name  varchar(120)  not null,
  driver_name    varchar(120)  not null,
  from_branch_id bigint        not null references branch(id),
  destination    varchar(255)  not null,
  started_at     timestamptz   not null,
  completed_at   timestamptz,
  total          numeric(14,2) not null check (total > 0),
  payment_method varchar(20)   not null,
  cancel_reason  varchar(255),
  cancelled_at   timestamptz,
  created_by     bigint        not null references app_user(id),
  created_at     timestamptz   not null,
  constraint ck_delivery_done      check ((status = 'DONE') = (completed_at is not null)),
  constraint ck_delivery_cancelled check ((status = 'CANCELLED') = (cancel_reason is not null and cancelled_at is not null))
);
create index ix_delivery_status_started on delivery (status, started_at desc);

-- Snapshot of what was sold: later package edits never change a past delivery.
create table delivery_line (
  delivery_id  bigint        not null references delivery(id),
  package_id   bigint        not null references sales_package(id),
  package_name varchar(120)  not null,
  quantity     bigint        not null check (quantity > 0),
  unit_price   numeric(14,2) not null check (unit_price > 0),
  line_total   numeric(14,2) not null check (line_total > 0),
  primary key (delivery_id, package_id)
);
