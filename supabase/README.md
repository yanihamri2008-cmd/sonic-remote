# Supabase

Your existing five tables and Realtime publication are already created.

Do not rerun the original schema blindly. Before enabling real sessions, replace direct client-side approval/session-state updates with a trusted server-side flow (for example, authenticated Edge Functions or carefully designed database functions). The Agent device must explicitly approve each request, and the backend must verify that approval before changing a session to `active`.

Never expose a service-role key to the Android client.
