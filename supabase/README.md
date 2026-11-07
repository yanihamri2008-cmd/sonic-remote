# SONIC REMOTE — Supabase backend

## Order of setup
1. **Database**: run `migrations/20261107000000_two_account_model.sql` in the SQL Editor (idempotent).
2. **Auth**: Authentication -> Providers -> Email enabled. For quick testing you may turn off
   "Confirm email"; for production leave it on.
3. **Edge Functions**: GitHub repo -> Settings -> Secrets and variables -> Actions, add
   `SUPABASE_ACCESS_TOKEN` (Account -> Access Tokens) and `SUPABASE_PROJECT_ID`
   (`afbhqesmbjxelnyzxjkc`), then run the **Deploy Supabase Edge Functions** workflow.
   `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are injected by Supabase automatically; never copy
   the service-role key anywhere else.
4. **Optional TURN** (needed for strict NATs/mobile carriers): Edge Functions -> Secrets:
   `TURN_URLS` (comma list, e.g. `turn:turn.example.com:3478`), `TURN_SHARED_SECRET` (coturn
   `static-auth-secret`), optional `STUN_URLS`. Without them only STUN is used (free, works on most networks).

## Security model
Clients only **read** `pairing_requests`, `remote_sessions`, `signaling_messages`, `audit_logs` (RLS,
participants only). All writes go through these functions (service role, caller JWT verified):

| Function | Caller | Does |
|---|---|---|
| `pairing-request` | Controller | `{requester_device_id, code}` -> creates a pending request (5 min), throttled to 5 wrong codes / 10 min |
| `pairing-respond` | Agent | `{request_id, approve}` -> atomic approve/reject; approve creates the session |
| `session-end` | either | `{session_id, revoke?}` -> ends (Agent may `revoke`) |
| `send-signal` | either | `{session_id, message_type, payload}` -> relays offer/answer/ICE to the other device |
| `ice-config` | either | `{session_id}` -> STUN + short-lived TURN credentials |

Tests: `tests/rls_test.sql` (run against `tests/legacy_baseline.sql` on a scratch Postgres).
