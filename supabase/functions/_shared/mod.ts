import { createClient, type SupabaseClient, type User } from "npm:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export class HttpError extends Error {
  constructor(public status: number, public code: string, message?: string) {
    super(message ?? code);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

let _admin: SupabaseClient | undefined;
/** Service-role client. The key exists ONLY in Edge Function secrets. */
export function admin(): SupabaseClient {
  if (!_admin) {
    const url = Deno.env.get("SUPABASE_URL");
    const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !key) throw new HttpError(500, "server_misconfigured");
    _admin = createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
  }
  return _admin;
}

/** Validates the caller's Supabase access token and returns the user. */
export async function authenticate(req: Request): Promise<User> {
  const m = /^Bearer (.+)$/i.exec(req.headers.get("Authorization") ?? "");
  if (!m) throw new HttpError(401, "missing_token");
  const { data, error } = await admin().auth.getUser(m[1]);
  if (error || !data.user) throw new HttpError(401, "invalid_token");
  return data.user;
}

type Body = Record<string, unknown>;

/** Wraps a POST-only JSON endpoint: CORS, auth, parsing, uniform errors. */
export function endpoint(fn: (user: User, body: Body) => Promise<unknown>) {
  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
    try {
      if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
      const user = await authenticate(req);
      let body: Body = {};
      try {
        const parsed = await req.json();
        if (parsed && typeof parsed === "object") body = parsed as Body;
      } catch { throw new HttpError(400, "invalid_json"); }
      return json(await fn(user, body));
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.code, message: e.message }, e.status);
      console.error("unhandled", e instanceof Error ? e.name : "error"); // never log secrets/payloads
      return json({ error: "internal_error" }, 500);
    }
  };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function uuidField(body: Body, name: string): string {
  const v = body[name];
  if (typeof v !== "string" || !UUID.test(v)) throw new HttpError(400, `invalid_${name}`);
  return v.toLowerCase();
}

export async function sha256Hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function maskEmail(email: string | undefined): string {
  if (!email) return "unknown";
  const [local, domain] = email.split("@");
  return `${local.slice(0, 1)}***@${domain ?? ""}`;
}

export async function audit(
  ownerId: string,
  eventType: string,
  extra: { deviceId?: string | null; sessionId?: string | null; details?: Record<string, unknown> } = {},
): Promise<void> {
  const { error } = await admin().from("audit_logs").insert({
    owner_id: ownerId,
    event_type: eventType,
    device_id: extra.deviceId ?? null,
    session_id: extra.sessionId ?? null,
    details: extra.details ?? {},
  });
  if (error) console.error("audit_failed", error.code);
}

/** Lazily expire stale pending requests and sessions so unique indexes never block on dead rows. */
export async function expireStale(deviceIds: string[]): Promise<void> {
  const now = new Date().toISOString();
  const db = admin();
  await db.from("pairing_requests").update({ status: "expired" })
    .eq("status", "pending").lt("expires_at", now)
    .or(`requester_device_id.in.(${deviceIds.join(",")}),target_device_id.in.(${deviceIds.join(",")})`);
  await db.from("remote_sessions")
    .update({ status: "ended", ended_at: now, end_reason: "expired" })
    .in("status", ["pending", "active"]).lt("expires_at", now)
    .or(`controller_device_id.in.(${deviceIds.join(",")}),agent_device_id.in.(${deviceIds.join(",")})`);
}
