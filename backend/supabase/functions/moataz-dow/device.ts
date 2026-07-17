import { TELEGRAM_BOT_USERNAME, db } from "./_shared/config.ts";
import {
  audit,
  authenticateDevice,
  bodyObject,
  json,
  sha256Hex,
} from "./_shared/utils.ts";

export async function handleDevice(
  req: Request,
  path: string,
): Promise<Response> {
  if (path === "/v1/device/register" && req.method === "POST") {
    const body = await bodyObject(req);
    const deviceId = String(body.device_id ?? "").trim();
    const deviceSecret = String(body.device_secret ?? "").trim();

    if (
      !/^[a-zA-Z0-9_-]{16,128}$/.test(deviceId) ||
      deviceSecret.length < 32
    ) return json({ error: "invalid_device_identity" }, 400);

    const hash = await sha256Hex(deviceSecret);
    const { data: existing } = await db.from("moataz_dow_devices")
      .select("device_id,device_secret_hash")
      .eq("device_id", deviceId)
      .maybeSingle();

    if (existing) {
      if (existing.device_secret_hash !== hash) {
        return json({ error: "device_identity_conflict" }, 409);
      }
      return json({ ok: true, registered: true });
    }

    const { error } = await db.from("moataz_dow_devices").insert({
      device_id: deviceId,
      device_secret_hash: hash,
      last_seen_at: new Date().toISOString(),
    });
    if (error) throw error;
    await audit("device_registered", "device", deviceId);
    return json({ ok: true, registered: true }, 201);
  }

  const device = await authenticateDevice(req);
  if (!device) return json({ error: "invalid_device_credentials" }, 401);

  if (path === "/v1/pair/code" && req.method === "POST") {
    await db.from("moataz_dow_pair_codes")
      .delete()
      .eq("device_id", device.device_id)
      .is("used_at", null);

    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let code = "";
    const random = crypto.getRandomValues(new Uint8Array(10));
    for (let i = 0; i < random.length; i++) {
      code += alphabet[random[i] % alphabet.length];
    }

    const expires = new Date(Date.now() + 10 * 60 * 1000).toISOString();
    const { error } = await db.from("moataz_dow_pair_codes").insert({
      code,
      device_id: device.device_id,
      expires_at: expires,
    });
    if (error) throw error;

    const username = TELEGRAM_BOT_USERNAME.replace(/^@/, "");
    return json({
      ok: true,
      code,
      expires_at: expires,
      bot_username: username,
      bot_url: username
        ? `https://t.me/${username}?start=${code}`
        : "",
    });
  }

  if (path === "/v1/device/status" && req.method === "GET") {
    return json({
      ok: true,
      paired: Boolean(device.telegram_chat_id),
      telegram_username: device.telegram_username ?? "",
      paired_at: device.paired_at,
    });
  }

  if (path === "/v1/device/unpair" && req.method === "POST") {
    await db.from("moataz_dow_devices")
      .update({
        telegram_chat_id: null,
        telegram_username: null,
        paired_at: null,
        updated_at: new Date().toISOString(),
      })
      .eq("device_id", device.device_id);
    await audit("device_unpaired", "device", device.device_id);
    return json({ ok: true });
  }

  if (path === "/v1/device/tasks" && req.method === "GET") {
    const { data, error } = await db.from("moataz_dow_tasks")
      .select("id,task_type,payload,status,created_at")
      .eq("device_id", device.device_id)
      .eq("status", "pending")
      .order("created_at", { ascending: true })
      .limit(25);
    if (error) throw error;

    const ids = (data ?? []).map((item) => item.id);
    if (ids.length) {
      await db.from("moataz_dow_tasks")
        .update({
          status: "delivered",
          delivered_at: new Date().toISOString(),
        })
        .in("id", ids);
    }
    return json({ ok: true, tasks: data ?? [] });
  }

  if (path === "/v1/device/ack" && req.method === "POST") {
    const body = await bodyObject(req);
    const taskId = String(body.task_id ?? "");
    const success = body.success !== false;

    const { error } = await db.from("moataz_dow_tasks")
      .update({
        status: success ? "completed" : "failed",
        completed_at: new Date().toISOString(),
        error: success
          ? null
          : String(body.error ?? "device_failed").slice(0, 500),
      })
      .eq("id", taskId)
      .eq("device_id", device.device_id);
    if (error) throw error;
    return json({ ok: true });
  }

  return json({ error: "not_found" }, 404);
}
