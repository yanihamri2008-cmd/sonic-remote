import { admin, audit, endpoint, expireStale, HttpError, maskEmail, sha256Hex, uuidField } from "../_shared/mod.ts";

const MAX_FAILED_ATTEMPTS = 5; // per 10 minutes per account

// Controller -> asks to connect to the Agent device that owns `code`.
Deno.serve(endpoint(async (user, body) => {
  const requesterDeviceId = uuidField(body, "requester_device_id");
  const code = String(body.code ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (code.length !== 8) throw new HttpError(400, "invalid_code");
  const db = admin();

  const since = new Date(Date.now() - 10 * 60_000).toISOString();
  const { count } = await db.from("audit_logs").select("id", { count: "exact", head: true })
    .eq("owner_id", user.id).eq("event_type", "pairing_code_failed").gte("created_at", since);
  if ((count ?? 0) >= MAX_FAILED_ATTEMPTS) throw new HttpError(429, "too_many_attempts");

  const { data: rd } = await db.from("devices")
    .select("id, owner_id, device_name, revoked_at").eq("id", requesterDeviceId).maybeSingle();
  if (!rd || rd.owner_id !== user.id || rd.revoked_at) throw new HttpError(403, "device_not_owned");

  const { data: td } = await db.from("devices")
    .select("id, owner_id, device_name, revoked_at").eq("pairing_code", code).maybeSingle();
  if (!td || td.revoked_at) {
    await audit(user.id, "pairing_code_failed", { deviceId: rd.id });
    throw new HttpError(404, "invalid_code"); // same answer whether the code never existed or was revoked
  }
  if (td.owner_id === user.id) throw new HttpError(400, "same_account");

  await expireStale([rd.id, td.id]);

  const { data: busy } = await db.from("remote_sessions").select("id")
    .eq("agent_device_id", td.id).eq("status", "active").limit(1);
  if (busy?.length) throw new HttpError(409, "agent_busy");

  const { data: req, error } = await db.from("pairing_requests").insert({
    requester_owner_id: user.id,
    target_owner_id: td.owner_id,
    requester_device_id: rd.id,
    target_device_id: td.id,
    code_hash: await sha256Hex(code),
    requester_device_name: rd.device_name,
    requester_account_label: maskEmail(user.email),
  }).select("id, expires_at").single();
  if (error) {
    if (error.code === "23505") throw new HttpError(409, "duplicate_request");
    throw new HttpError(500, "insert_failed");
  }

  await audit(user.id, "pairing_requested", { deviceId: rd.id, details: { request_id: req.id } });
  await audit(td.owner_id, "pairing_received", { deviceId: td.id, details: { request_id: req.id } });
  return { request_id: req.id, expires_at: req.expires_at, target_device_name: td.device_name };
}));
