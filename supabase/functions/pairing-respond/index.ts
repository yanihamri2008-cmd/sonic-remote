import { admin, audit, endpoint, expireStale, HttpError, uuidField } from "../_shared/mod.ts";

// Agent -> explicitly approves or rejects a request addressed to them.
Deno.serve(endpoint(async (user, body) => {
  const requestId = uuidField(body, "request_id");
  if (typeof body.approve !== "boolean") throw new HttpError(400, "invalid_approve");
  const approve = body.approve;
  const db = admin();

  const { data: pr } = await db.from("pairing_requests").select("*")
    .eq("id", requestId).eq("target_owner_id", user.id).maybeSingle(); // only the target account can see/answer it
  if (!pr) throw new HttpError(404, "not_found");
  if (pr.status !== "pending") throw new HttpError(409, "already_resolved");
  if (new Date(pr.expires_at) <= new Date()) {
    await db.from("pairing_requests").update({ status: "expired" }).eq("id", pr.id).eq("status", "pending");
    throw new HttpError(410, "expired");
  }

  const { data: devs } = await db.from("devices")
    .select("id, owner_id, device_name, revoked_at").in("id", [pr.requester_device_id, pr.target_device_id]);
  const cd = devs?.find((d) => d.id === pr.requester_device_id);
  const ad = devs?.find((d) => d.id === pr.target_device_id);
  if (!cd || !ad || cd.revoked_at || ad.revoked_at || ad.owner_id !== user.id || cd.owner_id !== pr.requester_owner_id) {
    await db.from("pairing_requests").update({ status: "rejected", responded_at: new Date().toISOString() })
      .eq("id", pr.id).eq("status", "pending");
    throw new HttpError(409, "device_unavailable");
  }

  // Atomic claim: only one caller can move pending -> approved/rejected (anti-replay).
  const now = new Date().toISOString();
  const { data: claimed } = await db.from("pairing_requests")
    .update({ status: approve ? "approved" : "rejected", responded_at: now })
    .eq("id", pr.id).eq("target_owner_id", user.id).eq("status", "pending").gt("expires_at", now)
    .select("id").maybeSingle();
  if (!claimed) throw new HttpError(409, "already_resolved");

  if (!approve) {
    await audit(user.id, "pairing_rejected", { deviceId: ad.id, details: { request_id: pr.id } });
    await audit(pr.requester_owner_id, "pairing_rejected_by_agent", { deviceId: cd.id, details: { request_id: pr.id } });
    return { status: "rejected" };
  }

  await expireStale([cd.id, ad.id]);
  const { data: session, error } = await db.from("remote_sessions").insert({
    controller_owner_id: pr.requester_owner_id,
    agent_owner_id: user.id,
    controller_device_id: cd.id,
    agent_device_id: ad.id,
    controller_device_name: cd.device_name,
    agent_device_name: ad.device_name,
    pairing_request_id: pr.id,
    status: "active", // the Agent just gave explicit consent
  }).select("id, expires_at").single();
  if (error) {
    // e.g. the agent is already in another active session; undo the approval
    await db.from("pairing_requests").update({ status: "rejected" }).eq("id", pr.id);
    throw new HttpError(error.code === "23505" ? 409 : 500, error.code === "23505" ? "agent_busy" : "session_failed");
  }

  await audit(user.id, "pairing_approved", { deviceId: ad.id, sessionId: session.id });
  await audit(pr.requester_owner_id, "session_started", { deviceId: cd.id, sessionId: session.id });
  return { status: "approved", session_id: session.id, expires_at: session.expires_at };
}));
