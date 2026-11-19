-- V7: idempotency keys for delivery creation.
-- A client sends an Idempotency-Key header when creating a delivery; this table remembers which delivery that key
-- produced, so a double submit (retry, double click) returns the original delivery instead of creating a second one.
-- The row is inserted in the same transaction as the delivery, so a key never exists without its delivery.
create table delivery_idempotency (
  -- Client-chosen key, 1-100 characters (length is also validated by the API).
  idempotency_key varchar(100) not null,
  -- The user who sent it. Keys are scoped per user, so two users can never see or collide with each other's keys.
  user_id         bigint       not null references app_user(id),
  -- The delivery the first request created; returned again for repeats.
  delivery_id     bigint       not null references delivery(id),
  -- When the key was first used (UTC instant).
  created_at      timestamptz  not null,
  -- Uniqueness of (key, user) is what guarantees at most one delivery per key even under concurrent requests.
  primary key (idempotency_key, user_id)
);

-- Supports looking up (and cleaning up) keys by delivery; the primary key already covers lookups by key and user.
create index delivery_idempotency_delivery_idx on delivery_idempotency (delivery_id);
