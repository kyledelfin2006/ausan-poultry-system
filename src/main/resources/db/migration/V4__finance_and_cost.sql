-- Weighted average cost per product and branch; updated atomically with the quantity.
alter table stock_balance
  add column avg_unit_cost numeric(14,4) not null default 0 check (avg_unit_cost >= 0);

-- Movements carry the cost per unit: purchase price on the way in, average cost at that moment on the way out.
alter table stock_movement alter column unit_cost type numeric(14,4);

-- Append-only financial ledger (PHP). Amounts are always positive; the type decides the meaning.
create table financial_entry (
  id             bigint generated always as identity primary key,
  type           varchar(20)   not null,
  cash_direction varchar(4)    not null,
  amount         numeric(14,2) not null check (amount > 0),
  method         varchar(20),
  occurred_on    date          not null,
  description    varchar(255),
  source_type    varchar(20),
  source_id      bigint,
  reverses_id    bigint unique references financial_entry(id),
  created_by     bigint        not null references app_user(id),
  created_at     timestamptz   not null,
  constraint ck_entry_type check (type in
    ('SALE','COST_OF_GOODS','PAYMENT_IN','REFUND','STOCK_PURCHASE','EXPENSE','SALE_REVERSAL','COGS_REVERSAL')),
  constraint ck_entry_direction check (
       (type = 'PAYMENT_IN'                                        and cash_direction = 'IN')
    or (type in ('REFUND','STOCK_PURCHASE','EXPENSE')              and cash_direction = 'OUT')
    or (type in ('SALE','COST_OF_GOODS','SALE_REVERSAL','COGS_REVERSAL') and cash_direction = 'NONE')),
  constraint ck_entry_method   check ((cash_direction = 'NONE') = (method is null)),
  constraint ck_entry_reversal check ((type in ('SALE_REVERSAL','COGS_REVERSAL')) = (reverses_id is not null))
);
create index ix_entry_date   on financial_entry (occurred_on, id);
create index ix_entry_source on financial_entry (source_type, source_id);

-- 20 PHP for a nipple is its purchase cost, not a selling price. Selling prices will come from packages (delivery slice).
update product set default_price = null where name = 'Nipple';
