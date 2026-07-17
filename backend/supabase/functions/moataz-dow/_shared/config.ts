import { createClient } from "npm:@supabase/supabase-js@2.49.4";

export const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
export const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
export const TELEGRAM_BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
export const TELEGRAM_BOT_USERNAME = Deno.env.get("TELEGRAM_BOT_USERNAME") ?? "";
export const TELEGRAM_WEBHOOK_SECRET = Deno.env.get("TELEGRAM_WEBHOOK_SECRET") ?? "";
export const ADMIN_USERNAME = Deno.env.get("ADMIN_USERNAME") ?? "moataz";
export const ADMIN_PASSWORD_SHA256 = Deno.env.get("ADMIN_PASSWORD_SHA256") ?? "";
export const ADMIN_SESSION_SECRET = Deno.env.get("ADMIN_SESSION_SECRET") ?? "";
export const PUBLIC_API_URL = Deno.env.get("PUBLIC_API_URL") ?? "";

export const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

export const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
  "access-control-allow-headers":
    "content-type,x-device-id,x-device-secret,x-telegram-bot-api-secret-token",
  "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
};
