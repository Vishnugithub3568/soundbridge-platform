create extension if not exists "pgcrypto";

create table if not exists migration_jobs (
    id uuid primary key default gen_random_uuid(),
    source_playlist_url text not null,
    target_platform varchar(64) not null,
    status varchar(32) not null,
    total_tracks integer not null default 0,
    matched_tracks integer not null default 0,
    failed_tracks integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists migration_tracks (
    id bigserial primary key,
    job_id uuid not null references migration_jobs(id) on delete cascade,
    source_track_name text not null,
    source_artist_name text not null,
    source_album_name text,
    target_track_id text,
    target_track_url text,
    match_status varchar(32) not null,
    confidence_score double precision,
    failure_reason text,
    created_at timestamptz not null default now()
);

create index if not exists idx_migration_tracks_job_id on migration_tracks(job_id);
