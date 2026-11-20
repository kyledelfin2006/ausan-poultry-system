-- V10: per-product reorder level for low-stock flags.
-- One optional row per product. A branch is "low" for that product when its quantity is at or below the level.
-- The level is business configuration, not a ledger: it is overwritten in place and only the latest value is kept.
create table stock_reorder_level (
  -- The product the level belongs to; a product has at most one level, so it is also the key.
  product_id    bigint      primary key references product(id),
  -- Units at or below which a branch counts as low on this product; 0 means "flag only when it is empty".
  reorder_level bigint      not null check (reorder_level >= 0),
  -- Who last set it and when (audit of the last change only).
  updated_by    bigint      not null references app_user(id),
  updated_at    timestamptz not null
);
