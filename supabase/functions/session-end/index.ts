import { admin, audit, endpoint, HttpError, uuidField } from "../_shared/mod.ts";

// Either participant can end a session. The Agent may mark it `revoked` (consent withdrawn).
Deno.serve(endpoint(async (user, body) => {
  const sessionId = uuidField(body, "session_id");
  const db = admin();

  const { data: s } = await db.from("remote_sessions").select("*").eq("id", sessionId).maybeSingle();
  if (!s || (s.controller_owner_id !== user.id && s.agent_owner_id !== user.id)) {
    throw new HttpError(404, "not_found"); // do not reveal sessions of other accounts
  }
  if (s.status === "ended" || s.status === "revoked") return { status: s.status };

  const isAgent = s.agent_owner_id === user.id;
  const revoke = body.revoke === true && isAgent;
  const reason = typeof body.reason === "string" ? body.reason.slice(0, 64) : revoke ? "agent_revoked" : "user_ended";
  const { data: updated } = await db.from("remote_sessions").update({
    status: revoke ? "revoked" : "ended",
    ended_at: new Date().toISOString(),
    ended_by: user.id,
    end_reason: reason,
  }).eq("id", s.id).in("status", ["pending", "active"]).select("status").maybeSingle();
  if (!updated) return { status: "ended" }; // someone else ended it first

  const ev = revoke ? "session_revoked" : "session_ended";
  await audit(user.id, ev, { sessionId: s.id, deviceId: isAgent ? s.agent_device_id : s.controller_device_id, details: { reason } });
  const other = isAgent ? s.controller_owner_id : s.agent_owner_id;
  await audit(other, `${ev}_by_peer`, { sessionId: s.id, details: { reason } });
  return { status: updated.status };
}));
