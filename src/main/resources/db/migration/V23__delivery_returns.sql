-- DES-3: a DONE delivery can come back as a whole (DONE -> RETURNED). The reason and time are stored apart from the
-- cancellation columns, and a returned delivery keeps its completion time, so the done-check is widened to match.
alter table delivery drop constraint delivery_status_check;
alter table delivery add constraint delivery_status_check check (status in ('ACTIVE','DONE','CANCELLED','RETURNED'));

alter table delivery add column return_reason varchar(255);
alter table delivery add column returned_at   timestamptz;

alter table delivery drop constraint ck_delivery_done;
alter table delivery add constraint ck_delivery_done check ((status in ('DONE','RETURNED')) = (completed_at is not null));
alter table delivery add constraint ck_delivery_returned check ((status = 'RETURNED') = (return_reason is not null and returned_at is not null));
