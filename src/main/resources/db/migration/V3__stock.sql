create table stock_balance (
  product_id bigint not null references product(id),
  branch_id  bigint not null references branch(id),
  quantity   bigint not null check (quantity >= 0),
  primary key (product_id, branch_id)
);

create table stock_movement (
  id             bigint generated always as identity primary key,
  product_id     bigint not null references product(id),
  branch_id      bigint not null references branch(id),
  type           varchar(20) not null,
  quantity_delta bigint not null check (quantity_delta <> 0),
  unit_cost      numeric(12,2) check (unit_cost >= 0),
  reason         varchar(255),          -- free text; for chickens, record the age (e.g. "6 weeks old") here
  occurred_on    date not null,
  source_type    varchar(20),
  source_id      bigint,
  reverses_id    bigint unique references stock_movement(id),
  created_by     bigint not null references app_user(id),
  created_at     timestamptz not null,
  constraint ck_movement_sign check (
       (type in ('OPENING','PURCHASE','DONATION','DELIVERY_REVERSAL') and quantity_delta > 0)
    or (type in ('USAGE','DELIVERY_OUT')                              and quantity_delta < 0)
    or  type = 'ADJUSTMENT'),
  constraint ck_movement_reason check (type not in ('ADJUSTMENT','USAGE') or reason is not null)
);
create index ix_movement_pb_date on stock_movement (product_id, branch_id, occurred_on desc, id desc);
create index ix_movement_source  on stock_movement (source_type, source_id);
