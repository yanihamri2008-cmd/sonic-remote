-- SONIC REMOTE: move from single-owner to two independent accounts.
-- Written against the schema inspected on 2026-11-07. Safe to re-run (idempotent).
-- Client roles get READ-ONLY access to pairing/session/signaling/audit data;
-- every write on those tables goes through Edge Functions (service role).

begin;

-- ---------------------------------------------------------------- helpers
create or replace function public.gen_pairing_code() returns text
language plpgsql volatile as $$
declare
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; -- no 0/O/1/I/L
  bytes bytea := decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex');
  result text := '';
  i int;
begin
  for i in 0..7 loop
    result := result || substr(alphabet, (get_byte(bytes, i) % 31) + 1, 1);
  end loop;
  return result;
end $$;

-- ---------------------------------------------------------------- devices
alter table public.devices add column if not exists pairing_code text not null default public.gen_pairing_code();
create unique index if not exists devices_pairing_code_key on public.devices (pairing_code);

-- a revoked device can never be un-revoked
create or replace function public.devices_guard() returns trigger
language plpgsql as $$
begin
  if old.revoked_at is not null and new.revoked_at is distinct from old.revoked_at then
    raise exception 'revoked device cannot be restored';
  end if;
  return new;
end $$;
drop trigger if exists devices_guard on public.devices;
create trigger devices_guard before update on public.devices
  for each row execute function public.devices_guard();

-- ------------------------------------------------------- pairing_requests
alter table public.pairing_requests
  add column if not exists requester_owner_id uuid references auth.users(id) on delete cascade,
  add column if not exists target_owner_id uuid references auth.users(id) on delete cascade,
  add column if not exists requester_device_name text,
  add column if not exists requester_account_label text,
  add column if not exists responded_at timestamptz;

update public.pairing_requests pr
   set requester_owner_id = coalesce(pr.requester_owner_id, pr.owner_id),
       target_owner_id    = coalesce(pr.target_owner_id,
                              (select d.owner_id from public.devices d where d.id = pr.target_device_id))
 where pr.requester_owner_id is null or pr.target_owner_id is null;

alter table public.pairing_requests
  alter column requester_owner_id set not null,
  alter column target_owner_id set not null,
  alter column owner_id drop not null;   -- legacy column, kept for compatibility
comment on column public.pairing_requests.owner_id is 'DEPRECATED: use requester_owner_id / target_owner_id';

-- one pending request per device pair
create unique index if not exists pairing_one_pending_per_pair
  on public.pairing_requests (requester_device_id, target_device_id) where status = 'pending';
create index if not exists pairing_target_owner_idx on public.pairing_requests (target_owner_id, status);

-- ---------------------------------------------------------- remote_sessions
alter table public.remote_sessions
  add column if not exists controller_owner_id uuid references auth.users(id) on delete cascade,
  add column if not exists agent_owner_id uuid references auth.users(id) on delete cascade,
  add column if not exists pairing_request_id uuid references public.pairing_requests(id),
  add column if not exists controller_device_name text,
  add column if not exists agent_device_name text,
  add column if not exists ended_by uuid references auth.users(id),
  add column if not exists end_reason text;

update public.remote_sessions s
   set controller_owner_id = coalesce(s.controller_owner_id,
         (select d.owner_id from public.devices d where d.id = s.controller_device_id)),
       agent_owner_id = coalesce(s.agent_owner_id,
         (select d.owner_id from public.devices d where d.id = s.agent_device_id))
 where s.controller_owner_id is null or s.agent_owner_id is null;

alter table public.remote_sessions
  alter column controller_owner_id set not null,
  alter column agent_owner_id set not null,
  alter column owner_id drop not null;   -- legacy column
comment on column public.remote_sessions.owner_id is 'DEPRECATED: use controller_owner_id / agent_owner_id';

-- an approved request can produce at most one session (anti-replay)
create unique index if not exists sessions_one_per_request
  on public.remote_sessions (pairing_request_id) where pairing_request_id is not null;
-- an agent device can be in at most one active session
create unique index if not exists sessions_one_active_per_agent
  on public.remote_sessions (agent_device_id) where status = 'active';
create index if not exists sessions_controller_idx on public.remote_sessions (controller_owner_id);
create index if not exists sessions_agent_idx on public.remote_sessions (agent_owner_id);

-- -------------------------------------------------------------- audit_logs
alter table public.audit_logs add column if not exists session_id uuid references public.remote_sessions(id) on delete set null;
create index if not exists audit_owner_idx on public.audit_logs (owner_id, created_at desc);

-- ---------------------------------------------------------- signaling index
create index if not exists signaling_session_idx on public.signaling_messages (session_id, created_at);

-- ------------------------------------------------------------------- RLS
-- drop every old policy on the four server-controlled tables + old signaling ones
do $$
declare r record;
begin
  for r in select tablename, policyname from pg_policies
            where schemaname = 'public'
              and tablename in ('pairing_requests','remote_sessions','signaling_messages','audit_logs')
  loop
    execute format('drop policy if exists %I on public.%I', r.policyname, r.tablename);
  end loop;
end $$;

create policy pairing_select_participant on public.pairing_requests for select to authenticated
  using ((select auth.uid()) in (requester_owner_id, target_owner_id));

create policy sessions_select_participant on public.remote_sessions for select to authenticated
  using ((select auth.uid()) in (controller_owner_id, agent_owner_id));

-- A phone only sees signaling addressed to one of ITS OWN devices, in an
-- active, unexpired session it participates in.
create policy signaling_select_recipient on public.signaling_messages for select to authenticated
  using (
    exists (select 1 from public.remote_sessions s
             where s.id = signaling_messages.session_id
               and s.status = 'active'
               and s.expires_at > now()
               and (select auth.uid()) in (s.controller_owner_id, s.agent_owner_id))
    and exists (select 1 from public.devices d
             where d.id = signaling_messages.recipient_device_id
               and d.owner_id = (select auth.uid()))
  );

create policy audit_select_own on public.audit_logs for select to authenticated
  using (owner_id = (select auth.uid()));

-- ------------------------------------------------------------ privileges
revoke all on public.pairing_requests, public.remote_sessions,
              public.signaling_messages, public.audit_logs from anon, authenticated;
grant select on public.pairing_requests, public.remote_sessions,
                public.signaling_messages, public.audit_logs to authenticated;

revoke all on public.devices from anon, authenticated;
grant select, delete on public.devices to authenticated;
grant insert (owner_id, device_name, platform, device_public_key) on public.devices to authenticated;
-- owner may only change these columns (never owner_id / id / pairing_code via client)
grant update (device_name, last_seen, revoked_at) on public.devices to authenticated;

-- ---------------------------------------------------------------- realtime
do $$
declare t text;
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    foreach t in array array['pairing_requests','remote_sessions'] loop
      if not exists (select 1 from pg_publication_tables
                      where pubname='supabase_realtime' and schemaname='public' and tablename=t) then
        execute format('alter publication supabase_realtime add table public.%I', t);
      end if;
    end loop;
  end if;
end $$;

commit;
