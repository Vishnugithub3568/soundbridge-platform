-- Purpose: harden migration_jobs/migration_tracks key constraints in an idempotent way.
-- Run in Supabase SQL Editor.

begin;

-- Data integrity prechecks.
-- These queries should return 0 rows (or 0 counts) before constraints are enforced.
select count(*) as null_ids from public.migration_jobs where id is null;

select id, count(*) as cnt
from public.migration_jobs
group by id
having count(*) > 1;

select count(*) as orphan_tracks
from public.migration_tracks t
left join public.migration_jobs j on j.id = t.job_id
where j.id is null;

-- Ensure job id cannot be null.
alter table if exists public.migration_jobs
    alter column id set not null;

-- Ensure primary key exists on migration_jobs(id).
do $$
begin
    if not exists (
        select 1
        from pg_constraint c
        where c.conrelid = 'public.migration_jobs'::regclass
          and c.contype = 'p'
    ) then
        alter table public.migration_jobs
            add constraint migration_jobs_pkey primary key (id);
    end if;
end $$;

-- Ensure FK from migration_tracks(job_id) to migration_jobs(id) exists.
do $$
begin
    if not exists (
        select 1
        from pg_constraint c
        where c.contype = 'f'
          and c.conrelid = 'public.migration_tracks'::regclass
          and c.confrelid = 'public.migration_jobs'::regclass
    ) then
        alter table public.migration_tracks
            add constraint fk_migration_tracks_job
            foreign key (job_id)
            references public.migration_jobs(id)
            on delete cascade;
    end if;
end $$;

-- Ensure supporting index exists for FK lookups.
create index if not exists idx_migration_tracks_job_id
    on public.migration_tracks(job_id);

commit;
