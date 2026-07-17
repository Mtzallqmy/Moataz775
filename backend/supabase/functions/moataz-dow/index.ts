import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { PUBLIC_API_URL, jsonHeaders } from "./_shared/config.ts";
import { json } from "./_shared/utils.ts";
import { handleAdmin } from "./admin.ts";
import { handleDevice } from "./device.ts";
import { handleTelegramWebhook } from "./telegram.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: jsonHeaders });
  }

  try {
    const url = new URL(req.url);
    let path = url.pathname.replace(/^\/moataz-dow/, "") || "/";
    if (!path.startsWith("/")) path = `/${path}`;

    if (path === "/" || path === "/health") {
      return json({
        ok: true,
        service: "moataz-dow",
        telegram_configured: Boolean(
          Deno.env.get("TELEGRAM_BOT_TOKEN") &&
            Deno.env.get("TELEGRAM_WEBHOOK_SECRET"),
        ),
        admin_configured: Boolean(
          Deno.env.get("ADMIN_PASSWORD_SHA256") &&
            Deno.env.get("ADMIN_SESSION_SECRET"),
        ),
        api_url: PUBLIC_API_URL ||
          `${url.origin}/functions/v1/moataz-dow`,
      });
    }

    if (
      path === "/telegram/webhook" &&
      req.method === "POST"
    ) return await handleTelegramWebhook(req);

    if (path.startsWith("/v1/")) return await handleDevice(req, path);
    if (path.startsWith("/admin")) return await handleAdmin(req, path);
    return json({ error: "not_found" }, 404);
  } catch (error) {
    console.error(error);
    return json({
      error: error instanceof Error ? error.message : "internal_error",
    }, 500);
  }
});
