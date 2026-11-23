-- MYtrack MVP schema. Apply this in the SQL Editor of a NEW Supabase project.
create table if not exists public.shared_locations (
  pair_code text not null,
  user_id text not null,
  latitude double precision not null,
  longitude double precision not null,
  updated_at timestamptz not null default now(),
  primary key (pair_code, user_id)
);

alter table public.shared_locations enable row level security;

-- MVP access: the pair code acts as a shared secret. Do not use personal or guessable codes.
-- Before public launch, replace these policies with authenticated-user membership policies.
create policy "pair members can read locations"
on public.shared_locations for select
to anon, authenticated
using (length(pair_code) >= 6);

create policy "pair members can insert locations"
on public.shared_locations for insert
to anon, authenticated
with check (length(pair_code) >= 6);

create policy "pair members can update locations"
on public.shared_locations for update
to anon, authenticated
using (length(pair_code) >= 6)
with check (length(pair_code) >= 6);

create policy "pair members can delete own location"
on public.shared_locations for delete
to anon, authenticated
using (length(pair_code) >= 6);
