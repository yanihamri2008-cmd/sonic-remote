import { admin, audit, endpoint, HttpError, uuidField } from "../_shared/mod.ts";

const TYPES = new Set(["offer", "answer", "ice_candidate"]);
const MAX_PAYLOAD_BYTES = 32 * 1024;

// Relays one WebRTC signaling message to the OTHER device of an active session.
// Sender/recipient are derived on the server from the session, never trusted from the client.
Deno.serve(endpoint(async (user, body) => {
  const sessionId = uuidField(body, "session_id");
  const type = body.message_type;
  if (typeof type !== "string" || !TYPES.has(type)) throw new HttpError(400, "invalid_message_type");
  const payload = body.payload;
  if (!payload || typeof payload !== "object" || JSON.stringify(payload).length > MAX_PAYLOAD_BYTES) {
    throw new HttpError(400, "invalid_payload");
  }
  const db = admin();

  const { data: s } = await db.from("remote_sessions").select("*").eq("id", sessionId).maybeSingle();
  if (!s || (s.controller_owner_id !== user.id && s.agent_owner_id !== user.id)) throw new HttpError(404, "not_found");
  if (s.status !== "active") throw new HttpError(409, "session_not_active");
  if (new Date(s.expires_at) <= new Date()) {
    await db.from("remote_sessions").update({ status: "ended", ended_at: new Date().toISOString(), end_reason: "expired" })
      .eq("id", s.id).eq("status", "active");
    throw new HttpError(410, "session_expired");
  }

  const isController = s.controller_owner_id === user.id;
  const sender = isController ? s.controller_device_id : s.agent_device_id;
  const recipient = isController ? s.agent_device_id : s.controller_device_id;

  // Optional sanity check: if the client names its device, it must be the session's device for its role.
  if (body.sender_device_id !== undefined && uuidField(body, "sender_device_id") !== sender) {
    throw new HttpError(403, "sender_mismatch");
  }
  const { data: dev } = await db.from("devices").select("owner_id, revoked_at").eq("id", sender).maybeSingle();
  if (!dev || dev.owner_id !== user.id || dev.revoked_at) throw new HttpError(403, "device_unavailable");

  const { error } = await db.from("signaling_messages").insert({
    session_id: s.id, sender_device_id: sender, recipient_device_id: recipient, message_type: type, payload,
  });
  if (error) throw new HttpError(500, "relay_failed");
  if (type !== "ice_candidate") await audit(user.id, `signal_${type}`, { sessionId: s.id, deviceId: sender });
  return { ok: true };
}));
