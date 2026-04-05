create extension if not exists "pgcrypto";

create table if not exists migration_jobs (
    id uuid primary key default gen_random_uuid(),
    source_playlist_url text not null,
    target_platform varchar(64) not null,
    status varchar(32) not null,
    total_tracks integer not null default 0,
    matched_tracks integer not null default 0,
    failed_tracks integer not null default 0,
    last_processed_index integer not null default 0,
    paused_reason text,
    quota_units_estimated integer,
    next_retry_time timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists track_match_cache (
    id bigserial primary key,
    user_id uuid,
    track_title text not null,
    artist text not null,
    normalized_track_title text not null,
    normalized_artist text not null,
    youtube_video_id text not null,
    created_at timestamptz not null default now()
);

create table if not exists migration_tracks (
    id bigserial primary key,
    job_id uuid not null references migration_jobs(id) on delete cascade,
    source_track_name text not null,
    source_artist_name text not null,
    source_album_name text,
    target_track_id text,
    target_track_url text,
    target_track_title text,
    target_thumbnail_url text,
    youtube_video_id text,
    youtube_title text,
    match_status varchar(32) not null,
    match_score double precision,
    confidence_score double precision,
    failure_reason text,
    created_at timestamptz not null default now()
);

alter table if exists migration_tracks add column if not exists target_track_title text;
alter table if exists migration_tracks add column if not exists target_thumbnail_url text;
alter table if exists migration_tracks add column if not exists youtube_video_id text;
alter table if exists migration_tracks add column if not exists youtube_title text;
alter table if exists migration_tracks add column if not exists match_score double precision;

create index if not exists idx_migration_tracks_job_id on migration_tracks(job_id);
create index if not exists idx_track_match_cache_lookup on track_match_cache(user_id, normalized_track_title, normalized_artist);
