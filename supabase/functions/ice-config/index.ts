import { admin, endpoint, HttpError, uuidField } from "../_shared/mod.ts";

// Returns ICE servers for an active session the caller participates in.
// TURN credentials are short-lived (coturn "use-auth-secret" scheme) and derived from a
// server-side secret, so no TURN password ever ships inside the APK.
//   Secrets (optional): TURN_URLS="turn:turn.example.com:3478,turns:turn.example.com:5349"
//                       TURN_SHARED_SECRET="<coturn static-auth-secret>"
//                       STUN_URLS="stun:stun.l.google.com:19302" (default)
Deno.serve(endpoint(async (user, body) => {
  const sessionId = uuidField(body, "session_id");
  const { data: s } = await admin().from("remote_sessions").select("*").eq("id", sessionId).maybeSingle();
  if (!s || (s.controller_owner_id !== user.id && s.agent_owner_id !== user.id)) throw new HttpError(404, "not_found");
  if (s.status !== "active" || new Date(s.expires_at) <= new Date()) throw new HttpError(409, "session_not_active");

  const stun = (Deno.env.get("STUN_URLS") ?? "stun:stun.l.google.com:19302").split(",").map((u) => u.trim()).filter(Boolean);
  const iceServers: Array<Record<string, unknown>> = [{ urls: stun }];

  const turnUrls = (Deno.env.get("TURN_URLS") ?? "").split(",").map((u) => u.trim()).filter(Boolean);
  const secret = Deno.env.get("TURN_SHARED_SECRET");
  const ttl = 3600;
  if (turnUrls.length && secret) {
    const username = `${Math.floor(Date.now() / 1000) + ttl}:${user.id}`;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
    const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(username));
    iceServers.push({ urls: turnUrls, username, credential: btoa(String.fromCharCode(...new Uint8Array(sig))) });
  }
  return { ice_servers: iceServers, ttl, turn_available: turnUrls.length > 0 && !!secret };
}));
