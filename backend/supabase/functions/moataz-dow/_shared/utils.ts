import {
  ADMIN_SESSION_SECRET,
  ADMIN_USERNAME,
  db,
  jsonHeaders,
} from "./config.ts";

const encoder = new TextEncoder();

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: jsonHeaders });
}

export function html(body: string, status = 200, headers: HeadersInit = {}): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy":
        "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
        "img-src data:; connect-src 'self'; form-action 'self'; base-uri 'none'; " +
        "frame-ancestors 'none'",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      "referrer-policy": "no-referrer",
      ...headers,
    },
  });
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return [...new Uint8Array(digest)]
    .map((v) => v.toString(16).padStart(2, "0"))
    .join("");
}

export function base64Url(bytes: Uint8Array): string {
  let value = "";
  for (const byte of bytes) value += String.fromCharCode(byte);
  return btoa(value)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

export function secureEqual(a: string, b: string): boolean {
  const max = Math.max(a.length, b.length);
  let diff = a.length ^ b.length;
  for (let i = 0; i < max; i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}

async function hmac(value: string): Promise<string> {
  if (!ADMIN_SESSION_SECRET) return "";
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(ADMIN_SESSION_SECRET),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return base64Url(new Uint8Array(signature));
}

function cookieValue(req: Request, name: string): string {
  const cookie = req.headers.get("cookie") ?? "";
  for (const item of cookie.split(";")) {
    const [key, ...rest] = item.trim().split("=");
    if (key === name) return decodeURIComponent(rest.join("="));
  }
  return "";
}

export async function createAdminSession(username: string): Promise<string> {
  const expires = Math.floor(Date.now() / 1000) + 8 * 60 * 60;
  const payload = `${username}.${expires}`;
  return `${payload}.${await hmac(payload)}`;
}

export async function isAdmin(req: Request): Promise<boolean> {
  const parts = cookieValue(req, "moataz_admin").split(".");
  if (parts.length !== 3 || !ADMIN_SESSION_SECRET) return false;
  const [username, expires, signature] = parts;
  if (
    username !== ADMIN_USERNAME ||
    Number(expires) < Math.floor(Date.now() / 1000)
  ) return false;
  return secureEqual(signature, await hmac(`${username}.${expires}`));
}

export async function bodyObject(
  req: Request,
): Promise<Record<string, unknown>> {
  const type = req.headers.get("content-type") ?? "";
  if (type.includes("application/json")) return await req.json();
  return Object.fromEntries((await req.formData()).entries());
}

export async function audit(
  eventType: string,
  actor = "",
  deviceId = "",
  details: Record<string, unknown> = {},
) {
  await db.from("moataz_dow_audit_logs").insert({
    event_type: eventType,
    actor,
    device_id: deviceId || null,
    details,
  });
}

export async function authenticateDevice(req: Request) {
  const deviceId = req.headers.get("x-device-id")?.trim() ?? "";
  const deviceSecret = req.headers.get("x-device-secret")?.trim() ?? "";
  if (!deviceId || !deviceSecret) return null;

  const hash = await sha256Hex(deviceSecret);
  const { data } = await db.from("moataz_dow_devices")
    .select(
      "device_id,telegram_chat_id,telegram_username,paired_at,disabled",
    )
    .eq("device_id", deviceId)
    .eq("device_secret_hash", hash)
    .maybeSingle();

  if (!data || data.disabled) return null;
  await db.from("moataz_dow_devices")
    .update({ last_seen_at: new Date().toISOString() })
    .eq("device_id", deviceId);
  return data;
}
