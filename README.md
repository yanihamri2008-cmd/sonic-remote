# SONIC REMOTE — by SONICTEAM

Transparent, consent-based Android remote support between two independent accounts
(**Controller** requests, **Agent** explicitly approves).

## Status
| Area | State |
|---|---|
| Branding, logo/icon, first-launch terms & disclaimer, AR/EN + RTL | implemented |
| Email/password registration, login, persistent encrypted session, logout | implemented |
| Backend: RLS migration + 5 Edge Functions (see `supabase/`) | implemented, tested locally (SQL) |
| Device registration (own pairing code, revoke), pairing requests (Realtime + poll fallback), approve/reject, session start/end | implemented (needs two-phone test) |
| WebRTC signaling (Realtime + REST catch-up), MediaProjection foreground service with Stop notification, Controller live view | implemented (needs two-phone test) |
| Remote touch control of the other phone | NOT available: Android only allows it via Accessibility, which this project deliberately does not use |

Nothing is marked finished until it has been built by CI and tested on two phones.

## Build
GitHub Actions builds the debug APK on every push to `main` (Actions -> latest run -> Artifacts).
Required repository secret: `SUPABASE_PUBLISHABLE_KEY` (the *publishable* key only — never a
secret/service-role key). Local builds: put `SUPABASE_PUBLISHABLE_KEY=...` in `local.properties`.

The debug APK is signed with the fixed, non-secret `keystore/sonicteam-debug.jks`, so new builds
install over old ones. A real release keystore must be created and kept as a GitHub secret
before any public release.

## Terms
Terms text lives in `res/values*/strings.xml` (`t1_*` ... `t8_*`). When you change them, bump
`Prefs.TERMS_VERSION` so every user is asked to accept again. Have a lawyer review the text
before public distribution.
