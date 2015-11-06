
-- name: queue-pop
-- Pop the top element off the backtick queue
update backtick_queue bq
set    state = 'running', started = now(), tries = tries + 1
from (
   select id
   from   backtick_queue
   where  state = 'queued'
   order by priority
   limit  1
   for update
   ) sub
where  bq.id = sub.id
returning bq.*;

-- name: queue-insert!
-- Insert a new job element
insert into backtick
  (name, priority, state, created_at, updated_at)
values
  (:name, :priority, :state, now(), now());

-- name: queue-finish!
-- Mark a job as finished
update backtick_cron
set    state = 'done', finished_at = now()
where  id = :id and state = 'running'

-- name: queue-killed-jobs
-- Find jobs that haven't finished in time
select * from backtick_queue where state = 'running' and started_at < :killtime

-- name: queue-abort-job!
-- Abort a job that has been tried too many times
update backtick_queue
set state = 'exceeded', finished_at = now()
where id = :id and state = 'running'

-- name: queue-requeue-job!
-- Put a job back in the queue that did not finish
update backtick_queue
set state = 'queued', priority = :priority, update_at = now()
where id = :id and state = 'running'

-- name: queue-delete-old-jobs!
-- Delete very old jobs
delete from backtick_queue where finished_at > :finished

-- name: cron-update-next!
-- Update the next runtime foran existing cron element
update backtick_cron
set    next = :next
where  id = :id

-- name: cron-all
-- Find all crons
SELECT * FROM backtick_cron;

-- name: cron-delete
-- Delete a cron entry
delete from backtick_cron where id = :id

-- name: cron-upsert-interval
-- Update the interval on an existing cron element or insert a new one
select backtick_upsert_interval(:name, :interval, :next);

-- name: cron-next
-- Get the next cron entry to run
update backtick_cron bc
set    next = :next
from (
   select id
   from   backtick_cron
   where  next < :now
   limit  1
   for update
   ) sub
where  bc.id = sub.id
returning bc.*;