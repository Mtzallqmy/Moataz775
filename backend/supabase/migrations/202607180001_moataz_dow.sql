create extension if not exists pgcrypto;

create table if not exists public.moataz_dow_devices (
  id uuid primary key default gen_random_uuid(),
  device_id text not null unique,
  device_secret_hash text not null,
  telegram_chat_id bigint unique,
  telegram_username text,
  paired_at timestamptz,
  disabled boolean not null default false,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.moataz_dow_pair_codes (
  code text primary key,
  device_id text not null references public.moataz_dow_devices(device_id) on delete cascade,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.moataz_dow_tasks (
  id uuid primary key default gen_random_uuid(),
  device_id text not null references public.moataz_dow_devices(device_id) on delete cascade,
  task_type text not null check (task_type in ('url', 'file', 'message')),
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'pending'
    check (status in ('pending', 'delivered', 'completed', 'failed')),
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  completed_at timestamptz,
  error text
);

create table if not exists public.moataz_dow_audit_logs (
  id bigint generated always as identity primary key,
  event_type text not null,
  actor text,
  device_id text,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists moataz_dow_pair_codes_device_idx
  on public.moataz_dow_pair_codes(device_id);
create index if not exists moataz_dow_pair_codes_expiry_idx
  on public.moataz_dow_pair_codes(expires_at);
create index if not exists moataz_dow_tasks_device_status_idx
  on public.moataz_dow_tasks(device_id, status, created_at);
create index if not exists moataz_dow_devices_chat_idx
  on public.moataz_dow_devices(telegram_chat_id)
  where telegram_chat_id is not null;

alter table public.moataz_dow_devices enable row level security;
alter table public.moataz_dow_pair_codes enable row level security;
alter table public.moataz_dow_tasks enable row level security;
alter table public.moataz_dow_audit_logs enable row level security;

revoke all on table public.moataz_dow_devices from anon, authenticated;
revoke all on table public.moataz_dow_pair_codes from anon, authenticated;
revoke all on table public.moataz_dow_tasks from anon, authenticated;
revoke all on table public.moataz_dow_audit_logs from anon, authenticated;

grant all on table public.moataz_dow_devices to service_role;
grant all on table public.moataz_dow_pair_codes to service_role;
grant all on table public.moataz_dow_tasks to service_role;
grant all on table public.moataz_dow_audit_logs to service_role;
grant usage, select on sequence public.moataz_dow_audit_logs_id_seq to service_role;
