import {
  TELEGRAM_BOT_TOKEN,
  TELEGRAM_WEBHOOK_SECRET,
  db,
} from "./_shared/config.ts";
import { audit, json, secureEqual } from "./_shared/utils.ts";

export async function telegram(
  method: string,
  payload: Record<string, unknown> = {},
) {
  if (!TELEGRAM_BOT_TOKEN) throw new Error("Telegram bot is not configured");
  const response = await fetch(
    `https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/${method}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    },
  );
  const result = await response.json();
  if (!response.ok || !result.ok) {
    throw new Error(
      result.description ?? `Telegram API HTTP ${response.status}`,
    );
  }
  return result.result;
}

async function sendMessage(chatId: number, text: string) {
  await telegram("sendMessage", {
    chat_id: chatId,
    text,
    disable_web_page_preview: true,
  });
}

function extractUrl(text: string): string {
  return text.match(/https?:\/\/[^\s<>"']+/i)?.[0]
    ?.replace(/[),.;]+$/, "") ?? "";
}

export async function handleTelegramWebhook(req: Request): Promise<Response> {
  const supplied = req.headers.get("x-telegram-bot-api-secret-token") ?? "";
  if (
    !TELEGRAM_WEBHOOK_SECRET ||
    !secureEqual(supplied, TELEGRAM_WEBHOOK_SECRET)
  ) return json({ error: "unauthorized" }, 401);

  const update = await req.json();
  const message = update.message ?? update.edited_message;
  if (!message?.chat?.id) return json({ ok: true });

  const chatId = Number(message.chat.id);
  const username = message.from?.username ?? "";
  const text = String(message.text ?? message.caption ?? "").trim();
  const normalized = text.replace(
    /^\/([a-zA-Z]+)@[^\s]+/,
    "/$1",
  );
  const lower = normalized.toLowerCase();

  if (lower.startsWith("/start")) {
    const code = normalized.split(/\s+/, 2)[1]?.trim() ?? "";
    if (!code) {
      await sendMessage(
        chatId,
        "مرحبًا بك في Moataz Dow. افتح التطبيق واضغط «ربط تيليجرام» ثم عد إلى هذا البوت.",
      );
      return json({ ok: true });
    }

    const { data: pair } = await db.from("moataz_dow_pair_codes")
      .select("code,device_id,expires_at,used_at")
      .eq("code", code)
      .maybeSingle();

    if (
      !pair ||
      pair.used_at ||
      new Date(pair.expires_at).getTime() < Date.now()
    ) {
      await sendMessage(
        chatId,
        "رمز الاقتران غير صالح أو انتهت مدته. أنشئ رمزًا جديدًا من التطبيق.",
      );
      return json({ ok: true });
    }

    await db.from("moataz_dow_devices")
      .update({
        telegram_chat_id: null,
        telegram_username: null,
        paired_at: null,
      })
      .eq("telegram_chat_id", chatId);

    const { error } = await db.from("moataz_dow_devices")
      .update({
        telegram_chat_id: chatId,
        telegram_username: username,
        paired_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      })
      .eq("device_id", pair.device_id);
    if (error) throw error;

    await db.from("moataz_dow_pair_codes")
      .update({ used_at: new Date().toISOString() })
      .eq("code", code);
    await audit(
      "device_paired",
      `telegram:${chatId}`,
      pair.device_id,
      { username },
    );
    await sendMessage(
      chatId,
      "تم ربط حسابك بتطبيق Moataz Dow بنجاح.\nأرسل رابطًا أو استخدم /download <الرابط>.",
    );
    return json({ ok: true });
  }

  const { data: device } = await db.from("moataz_dow_devices")
    .select("device_id,disabled")
    .eq("telegram_chat_id", chatId)
    .maybeSingle();

  if (!device || device.disabled) {
    await sendMessage(
      chatId,
      "هذا الحساب غير مرتبط بتطبيق. افتح Moataz Dow وأنشئ رمز اقتران جديدًا.",
    );
    return json({ ok: true });
  }

  if (lower.startsWith("/help")) {
    await sendMessage(
      chatId,
      "/download <الرابط> — إرسال رابط للتطبيق\n/formats — شرح الصيغ\n/status — حالة الربط\n/unlink — إلغاء الربط",
    );
    return json({ ok: true });
  }

  if (lower.startsWith("/formats")) {
    await sendMessage(
      chatId,
      "يعرض التطبيق الصيغ والدقات والصوت والترجمات المتاحة من نظام NewPipe، ثم تختار ما تريد تنزيله.",
    );
    return json({ ok: true });
  }

  if (lower.startsWith("/status")) {
    const { count } = await db.from("moataz_dow_tasks")
      .select("*", { count: "exact", head: true })
      .eq("device_id", device.device_id)
      .in("status", ["pending", "delivered"]);
    await sendMessage(
      chatId,
      `الربط يعمل. عدد الطلبات المنتظرة: ${count ?? 0}.`,
    );
    return json({ ok: true });
  }

  if (lower.startsWith("/unlink")) {
    await db.from("moataz_dow_devices")
      .update({
        telegram_chat_id: null,
        telegram_username: null,
        paired_at: null,
        updated_at: new Date().toISOString(),
      })
      .eq("device_id", device.device_id);
    await audit(
      "device_unpaired",
      `telegram:${chatId}`,
      device.device_id,
    );
    await sendMessage(
      chatId,
      "تم إلغاء الربط. يمكنك إنشاء رمز جديد من التطبيق في أي وقت.",
    );
    return json({ ok: true });
  }

  const url = extractUrl(normalized);
  if (lower.startsWith("/download") || url) {
    if (!url) {
      await sendMessage(
        chatId,
        "الاستخدام: /download https://example.com/media",
      );
      return json({ ok: true });
    }
    await db.from("moataz_dow_tasks").insert({
      device_id: device.device_id,
      task_type: "url",
      payload: { url, source: "telegram", chat_id: chatId },
    });
    await audit(
      "task_created",
      `telegram:${chatId}`,
      device.device_id,
      { type: "url" },
    );
    await sendMessage(
      chatId,
      "تم إرسال الرابط إلى تطبيق Moataz Dow. افتح التطبيق لاختيار الصيغة والجودة.",
    );
    return json({ ok: true });
  }

  await sendMessage(chatId, "أرسل رابطًا مباشرًا أو استخدم /help.");
  return json({ ok: true });
}
