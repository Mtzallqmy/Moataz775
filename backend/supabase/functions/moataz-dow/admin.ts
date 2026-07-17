import {
  ADMIN_PASSWORD_SHA256,
  ADMIN_SESSION_SECRET,
  ADMIN_USERNAME,
  db,
  jsonHeaders,
} from "./_shared/config.ts";
import {
  audit,
  bodyObject,
  createAdminSession,
  html,
  isAdmin,
  json,
  secureEqual,
  sha256Hex,
} from "./_shared/utils.ts";
import { telegram } from "./telegram.ts";

function adminPage(): string {
  return `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Moataz Dow Admin</title><style>
  :root{font-family:system-ui,sans-serif;color-scheme:dark}body{margin:0;background:#090d17;color:#eef2ff}.wrap{max-width:1100px;margin:auto;padding:24px}.card{background:#111827;border:1px solid #263244;border-radius:20px;padding:20px;margin:14px 0}input,button{font:inherit;border-radius:12px;padding:12px;border:1px solid #36445d}input{background:#0b1220;color:#fff;width:min(360px,90%)}button{background:#6d5dfc;color:#fff;cursor:pointer}button.secondary{background:#172033}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}.metric{font-size:2rem;font-weight:800}.muted{color:#9fb0ca}table{width:100%;border-collapse:collapse}td,th{padding:10px;border-bottom:1px solid #263244;text-align:right}.hidden{display:none}code{direction:ltr;display:inline-block}</style></head><body><div class="wrap">
  <h1>Moataz Dow</h1><p class="muted">لوحة إدارة البوت المركزي والأجهزة المرتبطة</p>
  <section id="login" class="card"><h2>تسجيل الدخول</h2><input id="username" autocomplete="username" placeholder="اسم المستخدم"><br><br><input id="password" type="password" autocomplete="current-password" placeholder="كلمة المرور"><br><br><button onclick="login()">دخول</button><p id="loginError"></p></section>
  <main id="dashboard" class="hidden"><div class="grid"><div class="card"><div class="muted">الأجهزة</div><div id="devicesCount" class="metric">—</div></div><div class="card"><div class="muted">المرتبطة</div><div id="pairedCount" class="metric">—</div></div><div class="card"><div class="muted">الطلبات المنتظرة</div><div id="tasksCount" class="metric">—</div></div></div>
  <div class="card"><h2>البوت</h2><button onclick="botTest()">فحص الاتصال</button> <button class="secondary" onclick="syncCommands()">مزامنة الأوامر</button> <button class="secondary" onclick="logout()">خروج</button><pre id="botResult"></pre></div>
  <div class="card"><h2>الأجهزة</h2><div style="overflow:auto"><table><thead><tr><th>الجهاز</th><th>تيليجرام</th><th>آخر ظهور</th><th></th></tr></thead><tbody id="devices"></tbody></table></div></div></main>
  </div><script>
  async function api(path,options={}){const r=await fetch(path,{...options,headers:{'content-type':'application/json',...(options.headers||{})}});if(r.status===401){showLogin();throw new Error('unauthorized')}const j=await r.json();if(!r.ok)throw new Error(j.error||'request_failed');return j}
  function showLogin(){login.classList.remove('hidden');dashboard.classList.add('hidden')}
  async function login(){loginError.textContent='';try{await api('./admin/login',{method:'POST',body:JSON.stringify({username:username.value,password:password.value})});login.classList.add('hidden');dashboard.classList.remove('hidden');load()}catch(e){loginError.textContent='بيانات الدخول غير صحيحة أو لم تُضبط الأسرار.'}}
  async function load(){try{const d=await api('./admin/api/overview');devicesCount.textContent=d.counts.devices;pairedCount.textContent=d.counts.paired;tasksCount.textContent=d.counts.pending;devices.innerHTML=d.devices.map(x=>'<tr><td><code>'+escapeHtml(x.device_id)+'</code></td><td>'+(x.telegram_username?'@'+escapeHtml(x.telegram_username):'—')+'</td><td>'+(x.last_seen_at?new Date(x.last_seen_at).toLocaleString():'—')+'</td><td><button class="secondary" data-id="'+escapeHtml(x.device_id)+'" onclick="unpair(this.dataset.id)">إلغاء الربط</button></td></tr>').join('')}catch(e){}}
  async function unpair(id){await api('./admin/api/unpair',{method:'POST',body:JSON.stringify({device_id:id})});load()}
  async function botTest(){botResult.textContent=JSON.stringify(await api('./admin/api/bot/test'),null,2)}
  async function syncCommands(){botResult.textContent=JSON.stringify(await api('./admin/api/bot/sync',{method:'POST'}),null,2)}
  async function logout(){await fetch('./admin/logout',{method:'POST'});showLogin()}
  function escapeHtml(s){return String(s).replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]))}
  load();</script></body></html>`;
}

export async function handleAdmin(
  req: Request,
  path: string,
): Promise<Response> {
  if (path === "/admin" || path === "/admin/") return html(adminPage());

  if (path === "/admin/login" && req.method === "POST") {
    if (!ADMIN_PASSWORD_SHA256 || !ADMIN_SESSION_SECRET) {
      return json({ error: "admin_not_configured" }, 503);
    }

    const body = await bodyObject(req);
    const username = String(body.username ?? "");
    const passwordHash = await sha256Hex(String(body.password ?? ""));

    if (
      !secureEqual(username, ADMIN_USERNAME) ||
      !secureEqual(passwordHash, ADMIN_PASSWORD_SHA256.toLowerCase())
    ) {
      await audit("admin_login_failed", username);
      return json({ error: "invalid_credentials" }, 401);
    }

    const session = await createAdminSession(username);
    await audit("admin_login", username);
    return new Response(JSON.stringify({ ok: true }), {
      headers: {
        ...jsonHeaders,
        "set-cookie":
          `moataz_admin=${encodeURIComponent(session)}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=28800`,
      },
    });
  }

  if (path === "/admin/logout" && req.method === "POST") {
    return new Response(JSON.stringify({ ok: true }), {
      headers: {
        ...jsonHeaders,
        "set-cookie":
          "moataz_admin=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0",
      },
    });
  }

  if (!await isAdmin(req)) return json({ error: "unauthorized" }, 401);

  if (path === "/admin/api/overview" && req.method === "GET") {
    const [
      { count: devices },
      { count: paired },
      { count: pending },
      deviceRows,
    ] = await Promise.all([
      db.from("moataz_dow_devices")
        .select("*", { count: "exact", head: true }),
      db.from("moataz_dow_devices")
        .select("*", { count: "exact", head: true })
        .not("telegram_chat_id", "is", null),
      db.from("moataz_dow_tasks")
        .select("*", { count: "exact", head: true })
        .in("status", ["pending", "delivered"]),
      db.from("moataz_dow_devices")
        .select(
          "device_id,telegram_username,paired_at,last_seen_at,disabled,created_at",
        )
        .order("created_at", { ascending: false })
        .limit(200),
    ]);

    return json({
      ok: true,
      counts: {
        devices: devices ?? 0,
        paired: paired ?? 0,
        pending: pending ?? 0,
      },
      devices: deviceRows.data ?? [],
    });
  }

  if (path === "/admin/api/unpair" && req.method === "POST") {
    const body = await bodyObject(req);
    const deviceId = String(body.device_id ?? "");
    await db.from("moataz_dow_devices")
      .update({
        telegram_chat_id: null,
        telegram_username: null,
        paired_at: null,
        updated_at: new Date().toISOString(),
      })
      .eq("device_id", deviceId);
    await audit("admin_unpair", ADMIN_USERNAME, deviceId);
    return json({ ok: true });
  }

  if (path === "/admin/api/bot/test" && req.method === "GET") {
    return json({
      ok: true,
      bot: await telegram("getMe"),
      webhook: await telegram("getWebhookInfo"),
    });
  }

  if (path === "/admin/api/bot/sync" && req.method === "POST") {
    const commands = [
      { command: "start", description: "ربط التطبيق أو عرض الترحيب" },
      { command: "download", description: "إرسال رابط إلى التطبيق" },
      { command: "formats", description: "شرح الصيغ المتاحة" },
      { command: "status", description: "عرض حالة الربط" },
      { command: "help", description: "عرض الأوامر" },
      { command: "unlink", description: "إلغاء ربط الجهاز" },
    ];
    await telegram("setMyCommands", { commands });
    return json({ ok: true, commands });
  }

  return json({ error: "not_found" }, 404);
}
