/* ホームパネル（iPad / ブラウザ版）
 * Home Assistant の /local/homepanel/ に置いて使う。Home Assistant と同じアドレスから読み込まれるので、
 * ログインは Home Assistant 標準のログイン画面で1回だけ。トークンはこの端末のブラウザ内に保存。
 */
"use strict";

(() => {
  const APP_VERSION = window.HP_VERSION || { version: "dev", code: 0 };
  const REPO = "sato3123/HA_A";
  const UPDATE_SERVICE = { domain: "shell_command", service: "update_homepanel" };
  // Home Assistant の /local/ に置いた場合と、専用サーバー（Proxmox の LXC）から配信する場合がある
  const SERVER_MODE = !location.pathname.startsWith("/local/");
  const SUPPORTED = ["climate", "light", "fan", "cover", "switch", "input_boolean", "scene", "script", "camera"];
  const ACTION_DOMAINS = SUPPORTED.filter((d) => d !== "camera");
  const SIMPLE = ["switch", "input_boolean", "scene", "script"];
  const TOGGLEABLE = ["light", "fan", "switch", "input_boolean"];

  // ================= 小道具 =================
  const $ = (s, el = document) => el.querySelector(s);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const store = {
    get(k, d = null) {
      try { const v = localStorage.getItem("hp_" + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; }
    },
    set(k, v) { try { localStorage.setItem("hp_" + k, JSON.stringify(v)); } catch (e) { /* 保存できない環境 */ } },
    del(k) { try { localStorage.removeItem("hp_" + k); } catch (e) { /* noop */ } },
  };
  const num = (a, k) => {
    const v = a ? a[k] : null;
    if (typeof v === "number") return isNaN(v) ? null : v;
    if (v === null || v === undefined || v === "" || typeof v === "boolean") return null;
    const n = Number(v);
    return isNaN(n) ? null : n;
  };
  const fmt = (d) => (Number.isInteger(d) ? String(d) : (Math.round(d * 10) / 10).toFixed(1));
  const dom = (e) => e.entity_id.split(".")[0];
  const nameOf = (e) => (e.attributes && e.attributes.friendly_name) || e.entity_id;
  const isUnav = (e) => e.state === "unavailable" || e.state === "unknown";
  const isOn = (e) => ["on", "open", "opening", "heat", "cool", "auto", "heat_cool", "dry", "fan_only"].includes(e.state);
  const colorModes = (e) => (e.attributes && e.attributes.supported_color_modes) || [];
  const dimmable = (e) => colorModes(e).some((m) => m !== "onoff");
  const hasPct = (e) => dom(e) === "fan" && e.attributes && "percentage" in e.attributes;
  const hasPos = (e) => dom(e) === "cover" && num(e.attributes, "current_position") !== null;
  const canStop = (e) => ((e.attributes && e.attributes.supported_features) & 8) !== 0;
  const isWide = (e) => ({ climate: true, light: dimmable(e), fan: hasPct(e), cover: hasPos(e), camera: true }[dom(e)] || false);

  // ================= 表示名 =================
  const DOMAIN_LABEL = { light: "照明", switch: "スイッチ", fan: "ファン", cover: "カーテン・シャッター", climate: "エアコン", input_boolean: "切り替え", scene: "シーン", script: "スクリプト", camera: "カメラ" };
  const HVAC = { off: "停止", heat: "暖房", cool: "冷房", auto: "自動", heat_cool: "冷暖自動", dry: "除湿", fan_only: "送風" };
  const FANM = { auto: "自動", low: "弱", medium: "中", middle: "中", mid: "中", high: "強", quiet: "静音", silent: "静音", turbo: "パワフル", strong: "パワフル", powerful: "パワフル", on: "オン", off: "オフ" };
  const SWING = { off: "固定", on: "スイング", vertical: "上下", horizontal: "左右", both: "上下左右" };
  const PRESET = { none: "なし", eco: "エコ", away: "外出", home: "在宅", boost: "ブースト", comfort: "快適", sleep: "おやすみ", activity: "活動", auto: "自動", natural: "自然風", nature: "自然風" };
  const lbl = (map) => (m) => map[String(m).toLowerCase()] || m;
  const hvacLabel = lbl(HVAC), fanLabel = lbl(FANM), swingLabel = lbl(SWING), presetLabel = lbl(PRESET);
  const COND = {
    sunny: ["☀️", "晴れ"], "clear-night": ["🌙", "晴れ（夜）"], partlycloudy: ["⛅", "晴れ時々くもり"], cloudy: ["☁️", "くもり"],
    fog: ["🌫️", "霧"], rainy: ["🌧️", "雨"], pouring: ["🌧️", "大雨"], lightning: ["⚡", "雷"], "lightning-rainy": ["⛈️", "雷雨"],
    snowy: ["❄️", "雪"], "snowy-rainy": ["🌨️", "みぞれ"], hail: ["🧊", "ひょう"], windy: ["💨", "強風"], "windy-variant": ["💨", "強風"], exceptional: ["⚠️", "警戒"],
  };
  const condEmoji = (c) => (COND[c] || ["🌡️"])[0];
  const condLabel = (c) => (COND[c] || [null, c])[1];

  function stateLabel(e) {
    if (e.state === "unavailable") return "利用できません";
    if (e.state === "unknown") return "不明";
    const a = e.attributes || {};
    switch (dom(e)) {
      case "scene": case "script": return "タップで実行";
      case "camera": return { idle: "待機中", streaming: "配信中", recording: "録画中" }[e.state] || e.state;
      case "cover": return { open: "開", closed: "閉", opening: "開いています…", closing: "閉じています…" }[e.state] || e.state;
      case "climate": {
        const cur = num(a, "current_temperature");
        return cur === null ? hvacLabel(e.state) : `${hvacLabel(e.state)}・室温 ${fmt(cur)}℃`;
      }
      case "light": {
        if (e.state !== "on") return "オフ";
        const b = num(a, "brightness");
        return b === null ? "オン" : `オン ${Math.round((b / 255) * 100)}%`;
      }
      default: return { on: "オン", off: "オフ" }[e.state] || isoLocal(e.state) || e.state;
    }
  }
  /** Home Assistant の時刻（UTC などの ISO 形式）を、日本時間の「11/4（水） 5:12」にする。時刻でなければ null */
  function isoLocal(v) {
    if (typeof v !== "string" || !/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?([+-]\d{2}:?\d{2}|Z)$/.test(v.trim())) return null;
    const ms = Date.parse(v.trim().replace(" ", "T"));
    if (isNaN(ms)) return null;
    const p = new Intl.DateTimeFormat("ja-JP", { timeZone: "Asia/Tokyo", month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit", weekday: "short", hourCycle: "h23" })
      .formatToParts(new Date(ms)).reduce((o, x) => ((o[x.type] = x.value), o), {});
    return `${p.month}/${p.day}（${p.weekday}） ${p.hour}:${p.minute}`;
  }

  // ================= 状態 =================
  const S = {
    ha: null,
    conn: "connecting",
    msg: null,
    pipelines: [],
    preferred: null,
    pipelineId: store.get("pipeline"),
    wake: store.get("wake", false),
    selection: store.get("selection"), // null = すべて表示
    weatherId: store.get("weather"),
    forecast: [],
    forecastFor: null,
    forecastSub: null,
    voice: { type: "idle" },
    lastDone: null,
    busy: false,
    stopReq: false,
    detail: null,
    dragging: null,
    page: null, // "settings" | "help"
    update: { type: "idle" },
    conv: null,
    theme: store.get("theme", "orange"),
    haAreas: [],
    entityAreas: {},
    customRooms: store.get("rooms", {}),
    roomOv: store.get("roomOv", {}),
    groupRooms: store.get("groupRooms", true),
    roomFilter: store.get("roomFilter", null),
    user: null,
    rules: {},
    rulesLoading: false,
    autoTab: 0,
    autoMsg: null,
    edit: null,
    editError: null,
    saving: false,
    timers: store.get("timers", []),
    ringing: [],
    ss: Object.assign({ enabled: false, minutes: 5, interval: 20, album: "" }, store.get("ss", {})),
    ssOn: false,
    photos: [],
    photoStatus: null,
    info: null,
    thermo: null,
    events: [],
    energy: null,
    entityDevice: {},
    deviceNames: {},
    cam: null,
    settingsPage: null,
    popupHidden: null,
  };
  const pendingTemp = new Map();

  // ================= 認証 =================
  const CLIENT_ID = location.origin + "/";
  const REDIRECT_URI = location.origin + location.pathname;
  class RevokedError extends Error {}

  async function tokenRequest(params) {
    const r = await fetch("/auth/token", { method: "POST", body: new URLSearchParams(params) });
    if (r.status === 400 || r.status === 403) throw new RevokedError("ログイン情報が無効になりました。もう一度ログインしてください");
    if (!r.ok) throw new Error("トークンを取得できませんでした (HTTP " + r.status + ")");
    return r.json();
  }

  function startLogin(slot) {
    const state = (slot === "admin" ? "admin-" : "") + Math.random().toString(36).slice(2) + Date.now().toString(36);
    store.set("state", state);
    location.href = "/auth/authorize?" + new URLSearchParams({ response_type: "code", client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, state });
  }

  async function handleCallback() {
    const q = new URLSearchParams(location.search);
    const code = q.get("code");
    if (!code) return;
    history.replaceState(null, "", location.pathname);
    if (q.get("state") !== store.get("state")) throw new Error("ログインの確認に失敗しました。もう一度お試しください");
    const t = await tokenRequest({ grant_type: "authorization_code", code, client_id: CLIENT_ID });
    if (String(q.get("state")).startsWith("admin-")) {
      // 管理者ログインの追加。普段使いのログインはそのまま
      try { await finishAdminLogin(t); store.set("flash", "管理者ログインを追加しました"); }
      catch (e) { store.set("flash", e.message); }
      store.set("openPage", "settings");
      return;
    }
    store.set("tokens", { access: t.access_token, refresh: t.refresh_token, expires: Date.now() + t.expires_in * 1000 });
  }

  let refreshing = null;
  async function accessToken() {
    const t = store.get("tokens");
    if (!t || !t.refresh) throw new RevokedError("ログインしていません");
    if (t.access && t.expires - 60000 > Date.now()) return t.access;
    if (!refreshing) {
      refreshing = tokenRequest({ grant_type: "refresh_token", refresh_token: t.refresh, client_id: CLIENT_ID })
        .then((r) => {
          t.access = r.access_token;
          t.expires = Date.now() + r.expires_in * 1000;
          store.set("tokens", t);
          return t.access;
        })
        .finally(() => { refreshing = null; });
    }
    return refreshing;
  }

  async function logout() {
    const t = store.get("tokens");
    if (t && t.refresh) {
      try { await fetch("/auth/token", { method: "POST", body: new URLSearchParams({ token: t.refresh, action: "revoke" }) }); } catch (e) { /* noop */ }
    }
    store.del("tokens");
    location.reload();
  }

  // ================= Home Assistant WebSocket =================
  class HA {
    constructor() {
      this.nextId = 1;
      this.pending = new Map();
      this.handlers = new Map();
      this.states = {};
      this.closed = new Promise((r) => { this._closed = r; });
    }

    connect(token) {
      return new Promise((resolve, reject) => {
        const ws = (this.ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/api/websocket"));
        ws.binaryType = "arraybuffer";
        const timer = setTimeout(() => { reject(new Error("サーバーの応答がありません")); ws.close(); }, 10000);
        ws.onmessage = (ev) => {
          const m = JSON.parse(ev.data);
          switch (m.type) {
            case "auth_required": ws.send(JSON.stringify({ type: "auth", access_token: token })); break;
            case "auth_ok": clearTimeout(timer); resolve(); break;
            case "auth_invalid": {
              clearTimeout(timer);
              const e = new Error("認証が無効です");
              e.authInvalid = true;
              reject(e);
              ws.close();
              break;
            }
            case "result": {
              const p = this.pending.get(m.id);
              if (p) {
                this.pending.delete(m.id);
                if (m.success) p.resolve(m.result); else p.reject(new Error((m.error && m.error.message) || "サーバーエラー"));
              }
              break;
            }
            case "event": { const h = this.handlers.get(m.id); if (h) h(m.event); break; }
            case "pong": { const p = this.pending.get(m.id); if (p) { this.pending.delete(m.id); p.resolve(true); } break; }
          }
        };
        ws.onclose = () => {
          clearTimeout(timer);
          reject(new Error("接続が切れました"));
          for (const p of this.pending.values()) p.reject(new Error("切断されました"));
          this.pending.clear();
          this._closed();
        };
      });
    }

    _send(msg, id, timeout = 15000) {
      return new Promise((resolve, reject) => {
        msg.id = id;
        this.pending.set(id, { resolve, reject });
        try { this.ws.send(JSON.stringify(msg)); } catch (e) { this.pending.delete(id); reject(e); return; }
        setTimeout(() => {
          if (this.pending.has(id)) { this.pending.delete(id); reject(new Error("タイムアウトしました")); }
        }, timeout);
      });
    }

    send(msg) { return this._send(msg, this.nextId++); }

    async subscribe(msg, cb) {
      const id = this.nextId++;
      this.handlers.set(id, cb);
      try { await this._send(msg, id); } catch (e) { this.handlers.delete(id); throw e; }
      return id;
    }

    unsub(id) { this.handlers.delete(id); }
    sendBinary(buf) { if (this.ws && this.ws.readyState === 1) this.ws.send(buf); }
    close() { if (this.ws) this.ws.close(); }

    call(domain, service, entity_id, data = {}) {
      return this.send({ type: "call_service", domain, service, target: entity_id ? { entity_id } : undefined, service_data: data });
    }
  }

  // ================= 接続ループ =================
  async function connectLoop() {
    let backoff = 2000;
    for (;;) {
      setConn("connecting");
      const ha = new HA();
      try {
        await ha.connect(await accessToken());
        S.ha = ha;
        const arr = await ha.send({ type: "get_states" });
        arr.forEach((s) => { ha.states[s.entity_id] = s; });
        await ha.subscribe({ type: "subscribe_events", event_type: "state_changed" }, (ev) => {
          const d = ev.data || {};
          if (d.new_state) ha.states[d.entity_id] = d.new_state; else delete ha.states[d.entity_id];
          if (d.entity_id && d.entity_id.startsWith("weather.")) renderWeather();
          if (d.entity_id && d.entity_id.startsWith("automation.") && S.page === "auto" && !S.edit) scheduleAuto();
          if (d.entity_id && d.entity_id.startsWith("sensor.")) scheduleThermos();
          scheduleRender();
        });
        setConn("connected");
        S.msg = null;
        backoff = 2000;
        renderEntities();
        renderWeather();
        renderThermos();
        renderQuick();
        loadEnergy();
        loadEvents();
        loadPipelines();
        loadRegistries();
        loadUser().then(() => { if (S.page === "auto") loadRules(); });
        kickWake();
        // 30秒ごとに Home Assistant に ping を送る。返事がなければ切れているとみなしてつなぎ直す
        // （iPad がスリープから戻ったあと、つながっているように見えて状態が届かないのを防ぐ）
        const hb = setInterval(() => {
          if (ha.ws.readyState !== 1) return;
          ha._send({ type: "ping" }, ha.nextId++, 10000).catch(() => { try { ha.ws.close(); } catch (e) { /* 閉じ済み */ } });
        }, 30000);
        await ha.closed;
        clearInterval(hb);
      } catch (e) {
        if (e instanceof RevokedError) { store.del("tokens"); showLogin(e.message); return; }
        if (e.authInvalid) {
          const t = store.get("tokens");
          if (t) { t.access = null; store.set("tokens", t); }
        } else {
          S.msg = "接続エラー: " + e.message;
        }
        ha.close();
      }
      S.ha = null;
      S.forecastFor = null;
      S.forecastSub = null;
      abortWake();
      setConn("retry");
      await sleep(backoff);
      backoff = Math.min(backoff * 2, 30000);
    }
  }

  async function loadPipelines() {
    try {
      const r = await S.ha.send({ type: "assist_pipeline/pipeline/list" });
      S.pipelines = (r.pipelines || []).map((p) => ({ id: p.id, name: p.name, wake: !!p.wake_word_entity, agent: p.conversation_engine || "" }));
      S.preferred = r.preferred_pipeline || null;
    } catch (e) { /* 一覧が取れなくても既定のアシスタントで動く */ }
  }

  const effectivePipeline = () => {
    const id = S.pipelineId;
    if (!id) return null;
    return S.pipelines.length === 0 || S.pipelines.some((p) => p.id === id) ? id : null;
  };

  function call(e, service, data = {}, domain = dom(e)) {
    if (!S.ha) return;
    S.ha.call(domain, service, e.entity_id, data).catch((err) => {
      S.msg = `「${nameOf(e)}」を操作できませんでした: ${err.message}`;
      renderMsg();
    });
    // 機器が切り替わるまで数秒かかるものや、Home Assistant が定期的にしか読まない機器でも、すぐ画面に反映されるよう読み直してもらう
    if (!["scene", "script", "camera", "automation"].includes(dom(e))) {
      [2500, 6000].forEach((ms) => setTimeout(() => { if (S.ha) S.ha.call("homeassistant", "update_entity", e.entity_id, {}).catch(() => {}); }, ms));
    }
  }
  /** 画面に戻ったときなどに、状態をまとめて読み直す（表示は変わったところだけ） */
  async function reloadStatesQuiet() {
    if (!S.ha) return;
    try {
      const arr = await S.ha.send({ type: "get_states" });
      arr.forEach((x) => { S.ha.states[x.entity_id] = x; });
      renderEntities(); renderThermos();
    } catch (e) { /* つながっていなければ接続のやり直しに任せる */ }
  }

  function primary(e) {
    const d = dom(e);
    if (d === "scene" || d === "script") call(e, "turn_on");
    else if (d === "cover") call(e, e.state === "open" || e.state === "opening" ? "close_cover" : "open_cover");
    else if (d === "climate") call(e, e.state === "off" ? "turn_on" : "turn_off");
    else call(e, "toggle");
  }

  // ================= 機器の一覧 =================
  function allEntities() {
    if (!S.ha) return [];
    return Object.values(S.ha.states)
      .filter((e) => SUPPORTED.includes(dom(e)))
      .sort((a, b) => SUPPORTED.indexOf(dom(a)) - SUPPORTED.indexOf(dom(b)) || nameOf(a).localeCompare(nameOf(b), "ja"));
  }
  function shownEntitiesAll() {
    const all = allEntities();
    return S.selection ? all.filter((e) => S.selection.includes(e.entity_id)) : all;
  }
  function shownEntities() {
    const list = shownEntitiesAll();
    const present = groupedByRoom(list).map(([r]) => r.key);
    if (!S.roomFilter || !present.includes(S.roomFilter)) return list;
    return list.filter((e) => roomKey(e.entity_id) === S.roomFilter);
  }
  function selectedSet() { return new Set(S.selection || allEntities().map((e) => e.entity_id)); }
  function saveSelection(set) { S.selection = [...set]; store.set("selection", S.selection); }

  // ---- 部品のHTML ----
  function headHTML(e, large) {
    const unav = isUnav(e);
    const sw = !unav && TOGGLEABLE.includes(dom(e))
      ? `<label class="sw"><input type="checkbox" data-act="toggle" ${e.state === "on" ? "checked" : ""}><span></span></label>`
      : "";
    const act = dom(e) === "camera" ? "none" : SIMPLE.includes(dom(e)) ? "primary" : "detail";
    return `<div class="head" data-act="${act}">${iconHTML(e)}
      <div class="ht"><div class="dom">${esc(DOMAIN_LABEL[dom(e)] || dom(e))}</div>
      <div class="name${large ? " large" : ""}">${esc(nameOf(e))}</div><div class="st">${esc(stateLabel(e))}</div></div>
      <button class="ibtn" data-set="info" data-id="${esc(e.entity_id)}" aria-label="詳細">ⓘ</button>${sw}</div>`;
  }

  const RANGE_LABEL = {
    brightness: (v) => `明るさ ${Math.round(v)}%`,
    colortemp: (v) => `色温度 ${Math.round(v)}K（左：電球色 ⇔ 右：昼光色）`,
    fan: (v) => `風量 ${Math.round(v)}%`,
    position: (v) => `開き具合 ${Math.round(v)}%`,
  };
  function rangeHTML(kind, value, min, max, step) {
    return `<div class="rng"><div class="rl">${esc(RANGE_LABEL[kind](value))}</div>
      <input type="range" data-act="range" data-kind="${kind}" min="${min}" max="${max}" step="${step}" value="${value}"></div>`;
  }
  function brightnessHTML(e) {
    const b = num(e.attributes, "brightness") || 0;
    return rangeHTML("brightness", e.state === "on" ? Math.round((b / 255) * 100) : 0, 0, 100, 1);
  }
  function colorTempHTML(e) {
    const a = e.attributes;
    const min = num(a, "min_color_temp_kelvin") || 2000, max = num(a, "max_color_temp_kelvin") || 6500;
    if (max <= min) return "";
    return rangeHTML("colortemp", num(a, "color_temp_kelvin") || Math.round((min + max) / 2), min, max, 50);
  }
  function fanHTML(e) {
    const step = Math.max(1, num(e.attributes, "percentage_step") || 1);
    return rangeHTML("fan", e.state === "on" ? num(e.attributes, "percentage") || 0 : 0, 0, 100, step);
  }
  function positionHTML(e) { return rangeHTML("position", num(e.attributes, "current_position") || 0, 0, 100, 1); }
  function coverButtons(e) {
    return `<div class="brow"><button class="tb" data-act="svc" data-svc="open_cover">▲ 開</button>
      ${canStop(e) ? `<button class="tb" data-act="svc" data-svc="stop_cover">■</button>` : ""}
      <button class="tb" data-act="svc" data-svc="close_cover">▼ 閉</button></div>`;
  }
  function chipsHTML(title, options, selected, svc, key, labelFn) {
    if (!options || !options.length) return "";
    return `<div class="chips">${title ? `<div class="ct">${esc(title)}</div>` : ""}${options
      .map((o) => `<button class="chip${o === selected ? " sel" : ""}" data-act="svc" data-svc="${svc}" data-key="${key}" data-val="${esc(o)}">${esc(labelFn(o))}</button>`)
      .join("")}</div>`;
  }
  function stepperHTML(e, key, label, value) {
    const pk = e.entity_id + "|" + key;
    const v = pendingTemp.has(pk) ? pendingTemp.get(pk).value : value;
    return `<div class="stepper"><span class="sl">${esc(label)}</span>
      <button class="tb" data-act="temp" data-key="${key}" data-delta="-1">−</button>
      <span class="tv" data-tv="${esc(pk)}">${fmt(v)}℃</span>
      <button class="tb" data-act="temp" data-key="${key}" data-delta="1">＋</button></div>`;
  }
  /** タイル用のエアコンの操作（小さくまとめる）：設定温度の −／＋ と、運転モード・風量の選択 */
  function climateTileHTML(e) {
    const a = e.attributes;
    const single = num(a, "temperature");
    const sel = (title, options, cur, svc, key, labelFn) => (options && options.length
      ? `<label class="msel"><span>${esc(title)}</span><select data-act="svcsel" data-svc="${svc}" data-key="${key}">${options
        .map((o) => `<option value="${esc(o)}" ${o === cur ? "selected" : ""}>${esc(labelFn(o))}</option>`).join("")}</select></label>` : "");
    let h = `<div class="ctl">`;
    if (e.state !== "off" && single !== null) {
      const pk = e.entity_id + "|temperature";
      const v = pendingTemp.has(pk) ? pendingTemp.get(pk).value : single;
      const mn = num(a, "min_temp") ?? 16, mx = num(a, "max_temp") ?? 32;
      h += `<span class="cgroup"><span class="cgl">設定温度${v <= mn ? "（最低）" : v >= mx ? "（最高）" : ""}</span><span class="cstep"><button class="tb${v <= mn ? " atlimit" : ""}" data-act="temp" data-key="temperature" data-delta="-1" aria-label="設定温度を下げる">−</button><span class="tv" data-tv="${esc(pk)}" aria-live="polite">${fmt(v)}℃</span><button class="tb${v >= mx ? " atlimit" : ""}" data-act="temp" data-key="temperature" data-delta="1" aria-label="設定温度を上げる">＋</button></span></span>`;
    }
    h += sel("運転", a.hvac_modes, e.state, "set_hvac_mode", "hvac_mode", hvacLabel);
    if (e.state !== "off") h += sel("風量", a.fan_modes, a.fan_mode, "set_fan_mode", "fan_mode", fanLabel);
    h += `</div>`;
    if (e.state !== "off" && single === null) h += tempHTML(e);
    return h;
  }
  function tempHTML(e) {
    if (e.state === "off") return "";
    const a = e.attributes;
    const single = num(a, "temperature"), low = num(a, "target_temp_low"), high = num(a, "target_temp_high");
    if (single !== null) return stepperHTML(e, "temperature", "設定温度", single);
    if (low !== null && high !== null) return stepperHTML(e, "target_temp_low", "下限", low) + stepperHTML(e, "target_temp_high", "上限", high);
    return "";
  }
  const PRESET_COLORS = [[0, 100, "#ff3b30"], [30, 100, "#ff9500"], [55, 100, "#ffe620"], [120, 100, "#34c759"], [190, 100, "#32d2f5"], [230, 100, "#2f5bff"], [280, 100, "#af52de"], [330, 60, "#ff7eb6"]];
  function colorsHTML() {
    return `<div><div class="rl" style="font-size:13px;margin-bottom:6px">色</div><div class="swatches">${PRESET_COLORS
      .map(([h, s, c]) => `<button class="swatch" style="background:${c}" data-act="color" data-h="${h}" data-s="${s}" aria-label="色"></button>`)
      .join("")}</div></div>`;
  }

  function tileHTML(e) {
    if (dom(e) === "camera") return cameraTileHTML(e);
    const d = dom(e), unav = isUnav(e);
    let body = "";
    if (!unav) {
      if (d === "light" && dimmable(e) && e.state === "on") body = brightnessHTML(e);
      else if (d === "fan" && hasPct(e)) body = fanHTML(e);
      else if (d === "cover") body = coverButtons(e) + (hasPos(e) ? positionHTML(e) : "");
      else if (d === "climate") body = climateTileHTML(e);
    }
    const cls = ["tile", isWide(e) ? "wide" : "", isOn(e) && d !== "scene" && d !== "script" ? "on" : "", unav ? "unav" : "", SIMPLE.includes(d) ? "simple" : ""].join(" ");
    return `<div class="${cls}" data-entity="${esc(e.entity_id)}">${headHTML(e, false)}${timerBadgeHTML(e)}${body}</div>`;
  }

  function detailHTML(e) {
    const d = dom(e), a = e.attributes || {};
    let h = headHTML(e, true);
    if (isUnav(e)) return h + "<div>この機器は現在利用できません</div>";
    if (d === "light") {
      if (dimmable(e)) h += brightnessHTML(e);
      if (colorModes(e).includes("color_temp")) h += colorTempHTML(e);
      if (colorModes(e).some((m) => ["hs", "xy", "rgb", "rgbw", "rgbww"].includes(m))) h += colorsHTML();
    } else if (d === "fan") {
      if (hasPct(e)) h += fanHTML(e);
      h += chipsHTML("モード", a.preset_modes, a.preset_mode, "set_preset_mode", "preset_mode", presetLabel);
      if ("oscillating" in a) {
        h += `<label class="row"><span class="rt">首振り</span><span class="sw"><input type="checkbox" data-act="osc" ${a.oscillating ? "checked" : ""}><span></span></span></label>`;
      }
    } else if (d === "cover") {
      h += coverButtons(e) + (hasPos(e) ? positionHTML(e) : "");
    } else if (d === "climate") {
      const info = [num(a, "current_temperature") !== null ? `室温 ${fmt(num(a, "current_temperature"))}℃` : null,
        num(a, "current_humidity") !== null ? `湿度 ${fmt(num(a, "current_humidity"))}%` : null].filter(Boolean);
      if (info.length) h += `<div class="info">${info.join("　")}</div>`;
      h += tempHTML(e);
      h += chipsHTML("運転モード", a.hvac_modes, e.state, "set_hvac_mode", "hvac_mode", hvacLabel);
      h += chipsHTML("風量", a.fan_modes, a.fan_mode, "set_fan_mode", "fan_mode", fanLabel);
      h += chipsHTML("風向き", a.swing_modes, a.swing_mode, "set_swing_mode", "swing_mode", swingLabel);
      h += chipsHTML("プリセット", a.preset_modes, a.preset_mode, "set_preset_mode", "preset_mode", presetLabel);
    } else {
      h += `<div class="brow"><button class="btn" data-act="primary">${d === "scene" || d === "script" ? "実行する" : "切り替える"}</button></div>`;
    }
    return h + devTimerSectionHTML(e);
  }

  // ---- 描画（操作中の機器は描き直さない） ----
  const tileCache = new Map(); // entity_id -> { html, el }
  const headerCache = new Map(); // 部屋の key -> 見出し
  const busy = (id) => S.dragging === id || [...pendingTemp.keys()].some((k) => k.startsWith(id + "|"));
  let autoQueued = false;
  function scheduleAuto() {
    if (autoQueued) return;
    autoQueued = true;
    setTimeout(() => { autoQueued = false; if (S.page === "auto" && !S.edit) renderAuto(); }, 500);
  }
  let renderQueued = false;
  function scheduleRender() {
    if (renderQueued) return;
    renderQueued = true;
    requestAnimationFrame(() => { renderQueued = false; renderEntities(); });
  }

  /** 部屋の見出しに出す温度・湿度（その部屋の温度計。無ければエアコンの室温） */
  function roomClimateHTML(room, items) {
    if (room.key === "none") return "";
    const t = thermos().find((x) => x.tId && x.temp !== null && roomKey(x.tId) === room.key);
    const ac = t ? null : items.find((e) => dom(e) === "climate" && num(e.attributes, "current_temperature") !== null);
    const temp = t ? t.temp : ac ? num(ac.attributes, "current_temperature") : null;
    const hum = t ? t.hum : ac ? num(ac.attributes, "current_humidity") : null;
    if (temp === null && hum === null) return "";
    return `<span class="rclim">${temp !== null ? `🌡 ${fmt(Math.round(temp * 10) / 10)}℃` : ""}${hum !== null ? `　💧 ${Math.round(hum)}%` : ""}${ac ? "（エアコン）" : ""}</span>`;
  }
  // ================= はじめての設定（4つの手順）と、かんたん表示 =================
  function renderGuide() {
    const box = $("#guide");
    if (!box) return;
    if (store.get("guideDone", false)) { box.innerHTML = ""; return; }
    const steps = [["rooms", "① 部屋を決める"], ["rooms", "② ホーム画面に出す機器を選ぶ"], ["voice", "③ 自宅の位置"], ["display", "④ 配色・家族ごとの表示"]];
    box.innerHTML = `<div class="card" style="padding:14px;margin-bottom:10px"><b>👋 はじめての設定（4つの手順）</b>
      <div class="note">順番に開いて設定すると、すぐに使い始められます。あとから ⚙ 設定 でいつでも変えられます。</div>
      ${steps.map(([k, t]) => `<div class="brow gstep" style="align-items:center"><button class="gname" data-set="guideOpen" data-id="${k}">${t} <span aria-hidden="true">›</span></button><button class="tbtn" data-set="guideOpen" data-id="${k}" aria-label="${t}を開く">開く</button></div>`).join("")}
      <div class="brow"><button class="tbtn" data-set="simpleOn">家族用に「かんたん表示」にする</button><span style="flex:1"></span><button class="tbtn" data-set="guideDone">終わった（もう出さない）</button></div></div>`;
  }
  const SIMPLE_DOMAINS = ["light", "switch", "input_boolean", "climate", "fan", "scene", "script", "cover"];
  function applySimple() {
    const on = store.get("simpleMode", false);
    const home = $("#home");
    if (home) home.classList.toggle("simple", on);
    const sb = $("#simpleBox");
    if (sb) sb.classList.toggle("hidden", !on);
    renderSimple();
  }
  function renderSimple() {
    const sb = $("#simpleBox");
    if (!sb || !store.get("simpleMode", false)) return;
    const list = shownEntitiesAll().filter((e) => SIMPLE_DOMAINS.includes(dom(e)));
    const icon = (d) => ({ light: "💡", climate: "❄", fan: "🌀", cover: "🪟", scene: "▶", script: "▶" }[d] || "🔌");
    const label = (e) => isUnav(e) ? "つながっていません" : ["scene", "script"].includes(dom(e)) ? "タップで実行" : dom(e) === "cover" ? (e.state === "open" ? "開いています" : "閉じています") : isOn(e) ? "ついています（タップで消す）" : "消えています（タップでつける）";
    sb.innerHTML = `<div class="simplegrid">${list.map((e) => `<button class="simpletile${isOn(e) ? " on" : ""}" data-set="simpleTap" data-id="${esc(e.entity_id)}" ${isUnav(e) ? "disabled" : ""}>
        <span class="si">${icon(dom(e))}</span><b>${esc(entName(e.entity_id))}</b><span class="ss">${label(e)}</span></button>`).join("") || `<div class="note">かんたん表示に出せる機器（照明・エアコン・スイッチなど）がありません（⚙ 設定 → 部屋と機器 で選べます）。</div>`}</div>
      <button class="tbtn" data-set="simpleOff" style="margin:8px 0">くわしい表示に戻す</button>`;
  }

  function renderEntities() {
    renderSimple();
    const grid = $("#grid");
    if (!grid) return;
    const list = shownEntities();
    const els = [];
    const multi = groupedByRoom(shownEntitiesAll()).length > 1;
    const sections = S.groupRooms && multi ? groupedByRoom(list) : [[null, list]];
    for (const [room, items] of sections) {
      if (room) {
        let h = headerCache.get(room.key);
        if (!h) { h = document.createElement("div"); h.className = "roomh"; headerCache.set(room.key, h); }
        h.innerHTML = esc(room.name) + roomClimateHTML(room, items);
        els.push(h);
      }
      for (const e of items) {
      const html = tileHTML(e);
      let c = tileCache.get(e.entity_id);
      if (!c || (c.html !== html && !busy(e.entity_id))) {
        const t = document.createElement("div");
        t.innerHTML = html;
        c = { html, el: t.firstElementChild };
        tileCache.set(e.entity_id, c);
      }
      els.push(c.el);
      }
    }
    const same = els.length === grid.children.length && els.every((el, i) => grid.children[i] === el);
    if (!same) {
      grid.replaceChildren(...els);
      grid.querySelectorAll("img[data-snap]").forEach((img) => { if (!img.getAttribute("src")) refreshSnapshot(img.dataset.snap); });
    }
    syncLiveTiles();
    renderRoomBar();
    $("#empty").classList.toggle("hidden", list.length > 0);
    $("#empty").textContent = S.conn === "connected" ? "表示する機器がありません（⚙ 設定 から選べます）" : "接続しています…";
    renderSheet();
  }

  function renderSheet() {
    const sheet = $("#sheet");
    const e = S.detail && S.ha && S.ha.states[S.detail];
    if (!e) { sheet.classList.add("hidden"); return; }
    sheet.classList.remove("hidden");
    if (busy(e.entity_id)) return;
    const box = $("#sheetContent");
    const html = detailHTML(e);
    if (box.dataset.html !== html) { box.innerHTML = html; box.dataset.html = html; }
  }

  // ---- 操作 ----
  function onTemp(e, key, delta) {
    const a = e.attributes;
    const step = num(a, "target_temp_step") || 0.5, min = num(a, "min_temp") ?? 16, max = num(a, "max_temp") ?? 32;
    const pk = e.entity_id + "|" + key;
    const cur = pendingTemp.has(pk) ? pendingTemp.get(pk).value : num(a, key);
    if (cur === null) return;
    let v = Math.round((cur + delta * step) / step) * step;
    v = Math.round(Math.min(max, Math.max(min, v)) * 10) / 10;
    const p = pendingTemp.get(pk) || {};
    clearTimeout(p.timer);
    p.value = v;
    // −／＋を連打しても、最後に押してから少し待ってまとめて送る
    p.timer = setTimeout(() => {
      pendingTemp.delete(pk);
      const fresh = (S.ha && S.ha.states[e.entity_id]) || e;
      const fa = fresh.attributes;
      const data = key === "temperature" ? { temperature: v } : {
        target_temp_low: key === "target_temp_low" ? v : num(fa, "target_temp_low"),
        target_temp_high: key === "target_temp_high" ? v : num(fa, "target_temp_high"),
      };
      call(fresh, "set_temperature", data);
      scheduleRender();
    }, 700);
    pendingTemp.set(pk, p);
    document.querySelectorAll(`[data-tv="${CSS.escape(pk)}"]`).forEach((el) => { el.textContent = fmt(v) + "℃"; });
  }

  function entityFor(el) {
    const tile = el.closest("[data-entity]");
    const id = tile ? tile.dataset.entity : S.detail;
    return id && S.ha ? S.ha.states[id] : null;
  }

  function handleAction(el) {
    const e = entityFor(el);
    if (!e) return;
    switch (el.dataset.act) {
      case "none": break;
      case "primary": primary(e); break;
      case "detail": S.detail = e.entity_id; renderSheet(); break;
      case "toggle": call(e, el.checked ? "turn_on" : "turn_off"); break;
      case "osc": call(e, "oscillate", { oscillating: el.checked }); break;
      case "svc": {
        const data = {};
        if (el.dataset.key) data[el.dataset.key] = el.dataset.val;
        call(e, el.dataset.svc, data);
        break;
      }
      case "temp": onTemp(e, el.dataset.key, Number(el.dataset.delta)); break;
      case "color": call(e, "turn_on", { hs_color: [Number(el.dataset.h), Number(el.dataset.s)] }); break;
    }
  }

  function commitRange(el) {
    const e = entityFor(el);
    if (!e) return;
    const v = Number(el.value);
    switch (el.dataset.kind) {
      case "brightness": if (v < 1) call(e, "turn_off"); else call(e, "turn_on", { brightness_pct: Math.round(v) }); break;
      case "colortemp": call(e, "turn_on", { color_temp_kelvin: Math.round(v) }); break;
      case "fan": if (v < 1) call(e, "turn_off"); else call(e, "set_percentage", { percentage: Math.round(v) }); break;
      case "position": call(e, "set_cover_position", { position: Math.round(v) }); break;
    }
  }

  // ================= キーボード・読み上げ（UI-007・UI-020） =================
  // ボタンでない押せる要素（data-set / data-act の div など）に、タブで届き Enter・Space で押せるようにする。
  // 押したときの処理は、これまでのクリックの処理をそのまま使う（呼び出しは増えない）
  const A11Y_SEL = "[data-set]:not(button):not(input):not(select):not(textarea):not(label):not(option):not(.dlg-bg):not(.sheet-bg), .head[data-act]";
  function a11yMark(root) {
    if (!root || !root.querySelectorAll) return;
    const list = root.matches && root.matches(A11Y_SEL) ? [root, ...root.querySelectorAll(A11Y_SEL)] : root.querySelectorAll(A11Y_SEL);
    for (const el of list) {
      if (el.dataset.act === "none") continue;
      if (!el.hasAttribute("tabindex")) el.setAttribute("tabindex", "0");
      if (!el.hasAttribute("role")) el.setAttribute("role", "button");
    }
    for (const c of root.querySelectorAll ? root.querySelectorAll(".chip, .tab") : []) c.setAttribute("aria-pressed", c.classList.contains("sel") ? "true" : "false");
    // UI-025：見出しの無い入力欄は、入力例（placeholder）を読み上げの名前にする
    for (const inp of root.querySelectorAll ? root.querySelectorAll("input[placeholder]:not([aria-label]), textarea[placeholder]:not([aria-label])") : []) {
      if (!inp.id || !document.querySelector(`label[for="${CSS.escape(inp.id)}"]`)) inp.setAttribute("aria-label", inp.getAttribute("placeholder"));
    }
    for (const inp of root.querySelectorAll ? root.querySelectorAll('label.sw input[type="checkbox"]:not([aria-label])') : []) {
      const tile = inp.closest(".tile, .auto, .row");
      const nm = tile && (tile.querySelector(".name, .an, .rt") || {}).textContent;
      if (nm) inp.setAttribute("aria-label", nm.trim().split("\n")[0].slice(0, 40) + "（入／切）");
    }
  }
  new MutationObserver((ms) => { for (const m of ms) for (const n of m.addedNodes) if (n.nodeType === 1) a11yMark(n); })
    .observe(document.documentElement, { childList: true, subtree: true });
  const MODALS = ["#sheet", "#info", "#thermoDlg", "#camBox", "#vpop", "#page", "#alarm"];

  const modalFocus = new Map();
  function openModal() {
    for (const sel of ["#alarm", "#camBox", "#info", "#thermoDlg", "#vpop", "#sheet", "#page"]) {
      const m = $(sel);
      if (m && !m.classList.contains("hidden")) return m;
    }
    return null;
  }
  function focusables(m) {
    return [...m.querySelectorAll('button, [href], input, select, textarea, [tabindex="0"]')].filter((x) => !x.disabled && x.offsetParent !== null);
  }
  // モーダルを開いたら中へ、閉じたら開く前の場所へフォーカスを移す
  new MutationObserver((ms) => {
    for (const m of ms) {
      const el = m.target;
      if (!el.id || !MODALS.includes("#" + el.id)) continue;
      const shown = !el.classList.contains("hidden");
      if (shown && !modalFocus.has(el)) {
        modalFocus.set(el, document.activeElement);
        setTimeout(() => { const f = focusables(el)[0]; if (f && !el.contains(document.activeElement)) try { f.focus({ preventScroll: true }); } catch (e) { /* なにもしない */ } }, 30);
      } else if (!shown && modalFocus.has(el)) {
        const back = modalFocus.get(el);
        modalFocus.delete(el);
        if (back && document.contains(back)) try { back.focus({ preventScroll: true }); } catch (e) { /* なにもしない */ }
      }
    }
  }).observe(document.documentElement, { attributes: true, attributeFilter: ["class"], subtree: true, attributeOldValue: false });
  document.addEventListener("keydown", (ev) => {
    const t = ev.target;
    if ((ev.key === "Enter" || ev.key === " ") && t && t.getAttribute && t.getAttribute("role") === "button" && !/^(BUTTON|INPUT|SELECT|TEXTAREA|LABEL|A)$/.test(t.tagName)) {
      ev.preventDefault();
      t.click();
      return;
    }
    const m = openModal();
    if (!m) return;
    if (ev.key === "Escape") {
      // それぞれの「閉じる」と同じ操作だけをする
      const close = m.querySelector('#sheetClose, [data-set="popClose"], [data-set="camClose"], [data-set="close"], [data-set="infoClose"], [data-set="thermoClose"], .dlg-x');
      if (close && !(m.id === "page" && S.edit)) { ev.preventDefault(); close.click(); }
      return;
    }
    if (ev.key === "Tab" && m.id !== "page") {
      const f = focusables(m);
      if (!f.length) return;
      if (ev.shiftKey && document.activeElement === f[0]) { ev.preventDefault(); f[f.length - 1].focus(); }
      else if (!ev.shiftKey && document.activeElement === f[f.length - 1]) { ev.preventDefault(); f[0].focus(); }
    }
  });

  function bindControls(root) {
    root.addEventListener("click", (ev) => {
      if (ev.target.closest("label.sw, input, select, label.msel")) return; // スイッチ・選択は change で処理
      if (ev.target.closest("[data-set]")) return; // ⓘ やカメラは別の処理
      const el = ev.target.closest("[data-act]");
      if (el) { handleAction(el); return; }
      const tile = ev.target.closest(".tile");
      if (tile) {
        const e = entityFor(tile);
        if (!e || isUnav(e)) return;
        if (dom(e) === "camera") openCamera(e.entity_id);
        else if (SIMPLE.includes(dom(e))) primary(e); else { S.detail = e.entity_id; renderSheet(); }
      }
    });
    root.addEventListener("change", (ev) => {
      const el = ev.target;
      if (el.dataset.act === "range") { commitRange(el); setTimeout(() => { S.dragging = null; scheduleRender(); }, 300); }
      else if (el.dataset.act === "toggle" || el.dataset.act === "osc") handleAction(el);
      else if (el.dataset.act === "svcsel") { const e = entityFor(el); if (e) call(e, el.dataset.svc, { [el.dataset.key]: el.value }); }
    });
    root.addEventListener("input", (ev) => {
      const el = ev.target;
      if (el.dataset.act !== "range") return;
      const e = entityFor(el);
      if (e) S.dragging = e.entity_id;
      const l = el.parentElement.querySelector(".rl");
      if (l) l.textContent = RANGE_LABEL[el.dataset.kind](Number(el.value));
    });
    const startDrag = (ev) => {
      if (ev.target.dataset && ev.target.dataset.act === "range") { const e = entityFor(ev.target); if (e) S.dragging = e.entity_id; }
    };
    root.addEventListener("pointerdown", startDrag);
    root.addEventListener("touchstart", startDrag, { passive: true });
  }

  // ================= 時計と天気 =================
  const DOW = ["日", "月", "火", "水", "木", "金", "土"];
  function renderClock() {
    // いつも日本時間で表示する（端末の時刻の設定にかかわらず）
    const p = jstParts(Date.now());
    const t = $("#time"), d = $("#date");
    if (!t) return;
    t.textContent = `${p.hour}:${p.minute}`;
    d.textContent = `${p.month}月${p.day}日（${p.weekday}）`;
  }

  function weatherEntity() {
    if (!S.ha) return null;
    const ws = Object.values(S.ha.states).filter((e) => e.entity_id.startsWith("weather.")).sort((a, b) => nameOf(a).localeCompare(nameOf(b), "ja"));
    return ws.find((w) => w.entity_id === S.weatherId) || ws[0] || null;
  }

  async function ensureForecast(w) {
    if (!S.ha || !w || S.forecastFor === w.entity_id) return;
    const ha = S.ha;
    if (S.forecastSub !== null) {
      ha.unsub(S.forecastSub);
      ha.send({ type: "unsubscribe_events", subscription: S.forecastSub }).catch(() => {});
    }
    S.forecastFor = w.entity_id;
    S.forecastSub = null;
    S.forecast = [];
    try {
      S.forecastSub = await ha.subscribe({ type: "weather/subscribe_forecast", forecast_type: "daily", entity_id: w.entity_id }, (ev) => {
        S.forecast = ev.forecast || [];
        renderWeather();
      });
    } catch (e) { /* 日別予報が無い天気連携もある */ }
  }

  function renderWeather() {
    const box = $("#weather");
    if (!box) return;
    const w = weatherEntity();
    if (!w || isUnav(w)) {
      box.innerHTML = `<div class="note">天気の情報がありません<br>（Home Assistant に天気の連携を追加してください）</div>`;
      return;
    }
    ensureForecast(w);
    const a = w.attributes || {};
    const unit = a.temperature_unit || "°C";
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const days = S.forecast.map((f) => { const d = new Date(f.datetime); d.setHours(0, 0, 0, 0); return [d, f]; }).filter(([d]) => !isNaN(d));
    const tf = (days.find(([d]) => d.getTime() === today.getTime()) || days[0] || [])[1];
    const sub = [
      tf && num(tf, "temperature") !== null ? `最高 ${fmt(num(tf, "temperature"))}°` : null,
      tf && num(tf, "templow") !== null ? `最低 ${fmt(num(tf, "templow"))}°` : null,
      tf && num(tf, "precipitation_probability") !== null ? `降水 ${fmt(num(tf, "precipitation_probability"))}%` : null,
      num(a, "humidity") !== null ? `湿度 ${fmt(num(a, "humidity"))}%` : null,
    ].filter(Boolean);
    const up = days.filter(([d]) => d > today).slice(0, 4);
    box.innerHTML = `<div class="wnow"><div class="wicon">${condEmoji(w.state)}</div><div>
        ${num(a, "temperature") !== null ? `<div class="wtemp">${fmt(num(a, "temperature"))}${esc(unit)}</div>` : ""}
        <div class="wcond">${esc(condLabel(w.state))}</div>${sub.length ? `<div class="wsub">${sub.join("　")}</div>` : ""}</div></div>
      ${up.length ? `<div class="wdays">${up.map(([d, f]) => `<div class="wday"><div>${DOW[d.getDay()]}</div><div class="e">${condEmoji(f.condition)}</div>
        <div>${[num(f, "temperature"), num(f, "templow")].filter((x) => x !== null).map(fmt).join("/")}°</div></div>`).join("")}</div>` : ""}`;
  }

  // ================= 音声 =================
  const player = new Audio();
  const SILENT = "data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQAAAAA=";
  let audioUnlocked = false;
  function unlockAudio() {
    if (audioUnlocked) return;
    audioUnlocked = true;
    player.src = SILENT;
    player.play().catch(() => {});
  }
  let playDone = null;
  function play(url) {
    return new Promise((res) => {
      let finished = false;
      const finish = () => { if (finished) return; finished = true; clearTimeout(t); playDone = null; try { player.pause(); } catch (e) { /* noop */ } res(); };
      // 読み上げが終わったと判定されなくても先へ進めるように（音声の長さ＋3秒、分からなければ30秒）
      let t = setTimeout(finish, 30000);
      player.onloadedmetadata = () => {
        if (isFinite(player.duration) && player.duration > 0) { clearTimeout(t); t = setTimeout(finish, (player.duration + 3) * 1000); }
      };
      playDone = finish;
      player.onended = finish;
      player.onerror = () => { addLog("読み上げの再生エラー"); finish(); };
      player.src = url;
      player.play().catch((e) => { addLog("読み上げを再生できません：" + e.message); finish(); });
    });
  }
  function stopSpeaking() { if (playDone) playDone(); }

  class Mic {
    constructor() { this.ctx = null; this.sink = null; }
    async ensure() {
      if (this.ctx) { if (this.ctx.state === "suspended") await this.ctx.resume().catch(() => {}); return; }
      if (!navigator.mediaDevices || !window.isSecureContext) throw new Error("このアドレス（http）では音声入力を使えません。https のアドレス（Home Assistant Cloud など）で開いてください");
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      const Ctx = window.AudioContext || window.webkitAudioContext;
      this.ctx = new Ctx();
      await this.ctx.audioWorklet.addModule("capture-worklet.js?v=" + encodeURIComponent(APP_VERSION.version));
      const src = this.ctx.createMediaStreamSource(this.stream);
      this.node = new AudioWorkletNode(this.ctx, "capture");
      const mute = this.ctx.createGain();
      mute.gain.value = 0;
      src.connect(this.node);
      this.node.connect(mute);
      mute.connect(this.ctx.destination);
      this.ratio = this.ctx.sampleRate / 16000;
      this.t = 0;
      this.carry = new Float32Array(0);
      this.node.port.onmessage = (ev) => this.onChunk(ev.data);
      if (this.ctx.state === "suspended") await this.ctx.resume().catch(() => {});
    }
    // 端末のサンプリング周波数（44.1/48kHz）→ 16kHz・16bit に変換して渡す
    onChunk(f32) {
      if (!this.sink && !this.levelCb) { this.carry = new Float32Array(0); this.t = 0; return; }
      const buf = new Float32Array(this.carry.length + f32.length);
      buf.set(this.carry, 0);
      buf.set(f32, this.carry.length);
      const out = [];
      let t = this.t;
      while (t < buf.length - 1) {
        const i = Math.floor(t), fr = t - i;
        out.push(buf[i] * (1 - fr) + buf[i + 1] * fr);
        t += this.ratio;
      }
      const used = Math.floor(t);
      this.carry = buf.slice(used);
      this.t = t - used;
      const pcm = new Int16Array(out.length);
      for (let i = 0; i < out.length; i++) { const s = Math.max(-1, Math.min(1, out[i])); pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff; }
      if (this.levelCb) {
        let sum = 0;
        for (let i = 0; i < out.length; i++) sum += out[i] * out[i];
        this.levelCb(Math.min(100, Math.round(Math.sqrt(sum / Math.max(1, out.length)) * 32768 / 3000 * 100)));
      }
      if (this.sink) this.sink(pcm);
    }
    beep() {
      if (!this.ctx) return;
      const o = this.ctx.createOscillator(), g = this.ctx.createGain();
      o.frequency.value = 880;
      g.gain.value = 0.15;
      o.connect(g); g.connect(this.ctx.destination);
      o.start(); o.stop(this.ctx.currentTime + 0.15);
    }
    stop() {
      this.sink = null;
      if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
      if (this.ctx) this.ctx.close().catch(() => {});
      this.ctx = null; this.stream = null;
    }
  }
  const mic = new Mic();

  function setVoice(v) {
    S.voice = v;
    if (v.type === "done") S.lastDone = v;
    renderVoice();
  }

  function errFor(d) {
    switch (d.code) {
      case "wake-engine-missing": case "wake-provider-missing":
        return new Error("呼びかけ検知が設定されていません（Home Assistant の音声アシスタントで「ウェイクワード」を設定してください）");
      case "stt-no-text-detected": return new Error("聞き取れませんでした");
      default: return new Error(d.message || "音声操作に失敗しました (" + d.code + ")");
    }
  }

  let currentAbort = null;
  class AbortErr extends Error {}
  let enhanceAudio = true;

  // ---- 動作ログ ----
  const voiceLog = [];
  function addLog(line) {
    const d = new Date();
    voiceLog.unshift(`${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${line}`);
    voiceLog.length = Math.min(voiceLog.length, 60);
    const box = $("#voiceLog");
    if (box) box.textContent = voiceLog.join("\n");
  }

  async function subscribeRun(ha, msg, cb) {
    try {
      return await ha.subscribe(msg, cb);
    } catch (e) {
      if (enhanceAudio && msg.input && "noise_suppression_level" in msg.input && /extra keys/i.test(e.message)) {
        enhanceAudio = false;
        addLog("マイク補正の指定に未対応のため外しました");
        delete msg.input.noise_suppression_level;
        delete msg.input.auto_gain_dbfs;
        return ha.subscribe(msg, cb);
      }
      throw e;
    }
  }

  function basePayload(start, end, input) {
    const msg = { type: "assist_pipeline/run", start_stage: start, end_stage: end, input };
    const pid = effectivePipeline();
    if (pid) msg.pipeline = pid;
    if (start === "intent" && S.conv && Date.now() - S.conv.at < 5 * 60 * 1000) msg.conversation_id = S.conv.id;
    return msg;
  }

  /** 聞き取って文字にする（wake: 呼びかけを待ってから）。呼びかけが無いまま時間切れなら null */
  async function listen(wake) {
    const ha = S.ha;
    if (!ha) throw new Error("接続していません");
    const input = Object.assign({ sample_rate: 16000 }, wake ? { timeout: 600 } : {}, enhanceAudio ? { noise_suppression_level: 2, auto_gain_dbfs: 15 } : {});
    let handler = -1, heard = null, ended = false, frames = 0;
    let deadline = wake ? Infinity : Date.now() + 10000;
    let resolveDone, rejectDone;
    const done = new Promise((res, rej) => { resolveDone = res; rejectDone = rej; });
    const stopAudio = () => {
      if (mic.sink === sink) mic.sink = null;
      if (handler >= 0 && !ended) { ended = true; ha.sendBinary(new Uint8Array([handler])); addLog(`音声送信を終了（約${Math.round(frames * 0.064)}秒分）`); }
    };
    const sink = (pcm) => {
      if (S.stopReq || Date.now() > deadline) { stopAudio(); return; }
      const frame = new Uint8Array(1 + pcm.length * 2);
      frame[0] = handler;
      frame.set(new Uint8Array(pcm.buffer), 1);
      ha.sendBinary(frame);
      frames += pcm.length / 1024;
    };
    S.stopReq = false;
    const subId = await subscribeRun(ha, basePayload(wake ? "wake_word" : "stt", "stt", input), (ev) => {
      const d = ev.data || {};
      if (ev.type !== "stt-vad-start") addLog("← " + ev.type);
      switch (ev.type) {
        case "run-start":
          handler = d.runner_data ? d.runner_data.stt_binary_handler_id : -1;
          if (handler === undefined || handler === null || handler < 0) { rejectDone(new Error("音声入力を開始できませんでした")); break; }
          setVoice({ type: wake ? "wait" : "listen" });
          mic.sink = sink;
          break;
        case "wake_word-end":
          if (!d.wake_word_output || !d.wake_word_output.wake_word_id) { resolveDone(); break; }
          addLog("呼びかけを検知：" + d.wake_word_output.wake_word_id);
          deadline = Date.now() + 10000;
          mic.beep();
          setVoice({ type: "listen" });
          break;
        case "stt-vad-end": stopAudio(); break;
        case "stt-end":
          stopAudio();
          heard = (d.stt_output && d.stt_output.text) || "";
          addLog(`聞き取り結果：「${heard}」`);
          break;
        case "error":
          addLog(`エラー：${d.code} ${d.message || ""}`);
          if (d.code === "wake-word-timeout") resolveDone(); else rejectDone(errFor(d));
          break;
        case "run-end": resolveDone(); break;
      }
    });
    addLog(wake ? "呼びかけの待ち受けを開始" : "聞き取りを開始");
    currentAbort = () => rejectDone(new AbortErr("中止"));
    const timer = setTimeout(() => rejectDone(new Error("時間切れになりました")), wake ? 690000 : 60000);
    try { await done; } finally { clearTimeout(timer); currentAbort = null; stopAudio(); ha.unsub(subId); }
    if (heard !== null && !heard.trim()) throw new Error("聞き取れませんでした");
    return heard;
  }

  /** 文字を会話エージェント（start=intent）または読み上げ（start=tts）に渡して、答えを読み上げる */
  async function runToTts(start, input, heard, fixedReply) {
    const ha = S.ha;
    if (!ha) throw new Error("接続していません");
    setVoice({ type: "proc", heard });
    let reply = fixedReply || "", tts = null, resolveDone, rejectDone;
    const done = new Promise((res, rej) => { resolveDone = res; rejectDone = rej; });
    const subId = await ha.subscribe(basePayload(start, "tts", input), (ev) => {
      const d = ev.data || {};
      addLog("← " + ev.type);
      switch (ev.type) {
        case "intent-end": {
          const o = d.intent_output || {};
          reply = (o.response && o.response.speech && o.response.speech.plain && o.response.speech.plain.speech) || "";
          if (o.conversation_id) S.conv = { id: o.conversation_id, at: Date.now() };
          break;
        }
        case "tts-end": tts = d.tts_output && d.tts_output.url; break;
        case "error": addLog(`エラー：${d.code} ${d.message || ""}`); rejectDone(errFor(d)); break;
        case "run-end": resolveDone(); break;
      }
    });
    const timer = setTimeout(() => rejectDone(new Error("時間切れになりました")), 90000);
    try { await done; } finally { clearTimeout(timer); ha.unsub(subId); }
    if (tts) {
      setVoice({ type: "speak", heard, reply });
      await play(tts);
    }
    setVoice({ type: "done", heard, reply });
  }

  /** 聞き取った文字を処理：タイマーなどアプリの命令はここで、それ以外は AI へ */
  async function handleHeard(text) {
    if (/予定/.test(text) && calendarEntities().length) {
      const off = /明日|あした|あす/.test(text) ? 1 : /今日|きょう|本日/.test(text) ? 0 : null;
      if (off !== null) {
        addLog("アプリの命令として処理：予定");
        if (!S.events.length) await loadEvents();
        const reply = speakDay(off);
        return runToTts("tts", { text: reply }, text, reply);
      }
    }
    const cmd = parseTimerCommand(text);
    if (!cmd) return runToTts("intent", { text: withHomeLocation(text) }, text);
    addLog("アプリの命令として処理：" + cmd.type);
    let reply;
    if (cmd.type === "start") {
      addTimer(cmd.seconds, cmd.label);
      reply = (cmd.label ? cmd.label + "の" : "") + speakRemaining(cmd.seconds * 1000) + "のタイマーをセットしました";
    } else if (cmd.type === "cancel") {
      const n = S.timers.length + S.ringing.length;
      stopAlarm();
      S.timers = []; saveTimers(); renderTimers();
      reply = n ? "タイマーを止めました" : "動いているタイマーはありません";
    } else {
      reply = S.timers.length ? S.timers.map((t) => (t.label || "タイマー") + "は残り" + speakRemaining(timerRemaining(t))).join("、") + "です" : "動いているタイマーはありません";
    }
    return runToTts("tts", { text: reply }, text, reply);
  }

  // ---- 呼びかけ待ち受けループ ----
  let wakeLoopPromise = null;
  const shouldWake = () => S.wake && S.ha && document.visibilityState === "visible" && !S.busy && window.isSecureContext;

  function kickWake() {
    if (!shouldWake() || wakeLoopPromise) return;
    wakeLoopPromise = (async () => {
      try {
        while (shouldWake()) {
          try {
            await mic.ensure();
            if (mic.ctx && mic.ctx.state !== "running") {
              setVoice({ type: "fail", msg: "画面をタップすると呼びかけの待ち受けを始めます" });
              await sleep(3000);
              continue;
            }
            const heard = await listen(true);
            if (heard) await handleHeard(heard);
            await sleep(300);
          } catch (e) {
            if (e instanceof AbortErr || !shouldWake()) break;
            addLog("失敗：" + e.message);
            setVoice({ type: "fail", msg: e.message });
            await sleep(5000);
          }
        }
      } finally {
        wakeLoopPromise = null;
        if (S.voice.type === "wait") setVoice({ type: "idle" });
      }
    })();
  }

  async function abortWake() {
    const p = wakeLoopPromise;
    if (currentAbort) currentAbort();
    stopSpeaking();
    if (p) await p.catch(() => {});
  }

  let pendingListen = false;
  /** マイクボタン：聞き取り中→終える／読み上げ中→止めて次を聞く／それ以外→聞き取り開始 */
  async function onMicPressed() {
    unlockAudio();
    onUserInteraction();
    if (S.voice.type === "listen") { S.stopReq = true; return; }
    if (S.busy) {
      // 処理中・読み上げ中でも、押したら「終わり次第すぐ次を聞く」。読み上げ中なら止める
      pendingListen = true;
      if (S.voice.type === "speak") stopSpeaking();
      setVoice(Object.assign({}, S.voice, { queued: true }));
      return;
    }
    if (!S.ha) return;
    S.busy = true;
    try {
      await abortWake();
      await mic.ensure();
      const heard = await listen(false);
      if (heard) await handleHeard(heard);
    } catch (e) {
      if (!(e instanceof AbortErr)) { addLog("失敗：" + e.message); setVoice({ type: "fail", msg: e.message }); }
    } finally {
      S.busy = false;
      if (pendingListen) { pendingListen = false; onMicPressed(); return; }
      if (!S.wake) mic.stop();
      kickWake();
    }
  }

  async function sendText(text) {
    unlockAudio();
    onUserInteraction();
    if (!text.trim() || !S.ha) return;
    if (S.busy) { if (S.voice.type === "speak") stopSpeaking(); else return; while (S.busy) await sleep(50); }
    S.busy = true;
    try {
      await abortWake();
      await handleHeard(text.trim());
    } catch (e) {
      if (!(e instanceof AbortErr)) { addLog("失敗：" + e.message); setVoice({ type: "fail", msg: e.message }); }
    } finally {
      S.busy = false;
      kickWake();
    }
  }

  /** マイクテスト（5秒）：音の大きさを表示 */
  async function micTest() {
    const out = $("#micTestResult"), bar = $("#micBar");
    if (S.busy) return;
    S.busy = true;
    try {
      await abortWake();
      await mic.ensure();
      out.textContent = "5秒間、話しかけてください…";
      let peak = 0;
      mic.levelCb = (lv) => { peak = Math.max(peak, lv); if (bar) bar.style.width = lv + "%"; };
      await sleep(5000);
      mic.levelCb = null;
      if (bar) bar.style.width = "0%";
      out.textContent = peak < 3 ? `音がほとんど入っていません（最大 ${peak}）。Safari のマイクの許可を確認してください`
        : peak < 15 ? `音が小さめです（最大 ${peak}）。もう少し近くで話すと認識しやすくなります` : `マイクは正常です（最大 ${peak}）`;
    } catch (e) {
      out.textContent = e.message;
    } finally {
      mic.levelCb = null;
      S.busy = false;
      if (!S.wake) mic.stop();
      kickWake();
    }
  }

  function renderVoice() {
    const st = $("#vstatus");
    if (!st) return;
    const v = S.voice;
    const micBtn = $("#mic");
    micBtn.classList.toggle("listen", v.type === "listen");
    micBtn.textContent = v.type === "listen" ? "■" : v.type === "speak" ? "🔇" : "🎤";
    micBtn.disabled = S.conn !== "connected" || !window.isSecureContext;
    st.textContent = {
      idle: S.wake ? "呼びかけの準備をしています…" : "マイクを押して話しかけてください",
      wait: "呼びかけを待っています（マイクボタンでもすぐに話せます）",
      listen: "聞いています…（話し終わったら■で終了）",
      proc: `「${v.heard || ""}」を処理しています…${v.queued ? "（終わったら続けて聞きます）" : ""}`,
      speak: `「${v.heard || ""}」🔊（マイクを押すと止めて次を聞けます）`,
      done: `「${v.heard || ""}」`,
      fail: v.msg || "",
    }[v.type];
    $("#vnote").textContent = window.isSecureContext ? "" : "※ http のアドレスでは音声入力が使えません。https のアドレスで開いてください。";
    renderPopup();
  }

  // ================= アップデート =================
  async function checkUpdate(silent) {
    if (!silent) { S.update = { type: "checking" }; renderUpdate(); }
    try {
      const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { cache: "no-store" });
      if (r.status === 404) throw new Error("更新情報が見つかりません（リポジトリが非公開か、まだリリースがありません）");
      if (!r.ok) throw new Error("更新を確認できませんでした (HTTP " + r.status + ")");
      const j = await r.json();
      const code = Number(String(j.tag_name || "").split(".").pop());
      const hasWeb = (j.assets || []).some((a) => a.name === "homepanel-web.zip");
      if (hasWeb && code > (APP_VERSION.code || 0)) S.update = { type: "available", version: j.tag_name.replace(/^v/, ""), notes: (j.body || "").trim() };
      else S.update = silent ? { type: "idle" } : { type: "uptodate" };
    } catch (e) {
      S.update = silent ? { type: "idle" } : { type: "failed", msg: e.message };
    }
    renderUpdate();
  }

  /** 専用サーバー方式：サーバーが5分おきに自動更新するので、新しい版に入れ替わるのを待って読み込み直す */
  async function waitServerUpdate() {
    S.update = { type: "updating", server: true };
    renderUpdate();
    const until = Date.now() + 12 * 60 * 1000;
    while (Date.now() < until) {
      try {
        const r = await fetch("version.json", { cache: "no-store" });
        const v = await r.json();
        if ((v.code || 0) > (APP_VERSION.code || 0)) { location.reload(); return; }
      } catch (e) { /* 次の確認で */ }
      await sleep(20000);
    }
    S.update = { type: "failed", msg: "まだ新しい版に入れ替わっていません。しばらくしてから画面を読み込み直してください（サーバーで「homepanel-update」を実行するとすぐに更新されます）。" };
    renderUpdate();
  }

  async function doUpdate() {
    if (SERVER_MODE) { waitServerUpdate(); return; }
    if (!S.ha) return;
    const prev = S.update;
    S.update = { type: "updating" };
    renderUpdate();
    try {
      await S.ha.send({ type: "call_service", domain: UPDATE_SERVICE.domain, service: UPDATE_SERVICE.service, service_data: {} });
      await sleep(1500);
      location.reload();
    } catch (e) {
      S.update = {
        type: "failed",
        msg: /not found|見つかり/i.test(e.message)
          ? "Home Assistant に更新用の設定（shell_command.update_homepanel）がありません。「？ 使い方 → アップデート」をご覧ください。"
          : "更新できませんでした: " + e.message,
        retry: prev.type === "available" ? prev : null,
      };
      renderUpdate();
    }
  }

  function updateHTML(showIdle) {
    const u = S.update;
    const cur = APP_VERSION.version;
    switch (u.type) {
      case "available":
        return `<div class="update"><b>⬆️ 新しいバージョン ${esc(u.version)} があります（今は ${esc(cur)}）</b>
          ${u.notes ? `<div class="note" style="color:inherit">${esc(u.notes.split("\n").slice(0, 4).join("\n"))}</div>` : ""}
          <div><button class="btn" data-set="doUpdate">更新する</button></div></div>`;
      case "updating": return `<div class="update">${u.server ? "サーバーが新しい版に入れ替えるのを待っています（最大5分ほど）。入れ替わると自動で読み込み直します…" : "更新しています…（終わると自動で再読み込みします）"}</div>`;
      case "checking": return showIdle ? `<div class="update">更新を確認しています…</div>` : "";
      case "uptodate": return showIdle ? `<div class="update">最新のバージョンです（${esc(cur)}）<div><button class="btn outline" data-set="checkUpdate">もう一度確認</button></div></div>` : "";
      case "failed":
        return `<div class="update"><span class="warn">${esc(u.msg)}</span><div>${u.retry
          ? `<button class="btn" data-set="doUpdate">もう一度試す</button>` : `<button class="btn outline" data-set="checkUpdate">もう一度確認</button>`}</div></div>`;
      default:
        return showIdle ? `<div class="update">アプリのバージョン：${esc(cur)}<div><button class="btn outline" data-set="checkUpdate">更新を確認</button></div></div>` : "";
    }
  }
  function renderUpdate() {
    const b = $("#updateBanner");
    if (b) b.innerHTML = updateHTML(false);
    document.querySelectorAll("[data-update-slot]").forEach((el) => { el.innerHTML = updateHTML(true); });
  }

  // ================= 設定・使い方ページ =================
  function settingsHTML() {
    if (S.settingsPage) {
      const m = SETTINGS_MENU.find((x) => x[0] === S.settingsPage) || ["", "", "設定"];
      return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="menuBack">← 設定</button><h2>${esc(m[2])}</h2></div>
        ${settingsPageHTML(S.settingsPage)}<div style="height:40px"></div></div>`;
    }
    const row = ([k, icon, title, sub]) => `<button class="menurow" data-set="menu" data-id="${k}"><span class="mi">${icon}</span>
        <span class="rt"><b>${esc(title)}</b><div class="sub">${esc(sub)}</div></span><span class="chev">›</span></button>`;
    return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="close">← 戻る</button><h2>設定</h2></div>
      <div class="sec">よく使う設定</div>
      ${SETTINGS_MENU.filter((m) => !m[4]).map(row).join("")}
      <button class="advtoggle" data-set="advToggle" aria-expanded="${S.showAdv ? "true" : "false"}"><span>詳しい設定</span><span class="sub">カメラ・接続・アカウント・記録など</span><span class="chev">${S.showAdv ? "▲ 閉じる" : "▼ 開く"}</span></button>
      ${S.showAdv ? SETTINGS_MENU.filter((m) => m[4]).map(row).join("") : ""}
      <div style="height:40px"></div></div>`;
  }
  function renderSelList() {
    const list = $("#selList");
    if (!list) return;
    const all = allEntities();
    const sel = selectedSet();
    const q = (($(".search") || {}).value || "").trim().toLowerCase();
    const filtered = all.filter((e) => !q || nameOf(e).toLowerCase().includes(q) || e.entity_id.includes(q));
    let html = "", last = null;
    for (const e of filtered) {
      if (dom(e) !== last) { last = dom(e); html += `<div class="gtitle">${esc(DOMAIN_LABEL[last] || last)}</div>`; }
      const rk = roomKey(e.entity_id);
      const ov = S.roomOv[e.entity_id];
      html += `<div class="row" style="cursor:default"><label class="row" style="flex:1;padding:0"><input type="checkbox" data-set="sel" data-id="${esc(e.entity_id)}" ${sel.has(e.entity_id) ? "checked" : ""}>
        <span class="rt">${esc(nameOf(e))}<div class="sub">${esc(e.entity_id)}</div></span></label>
        <select class="roomsel" data-set="room" data-id="${esc(e.entity_id)}" aria-label="部屋">
          ${rooms().map((r) => `<option value="${esc(r.key)}" ${r.key === rk ? "selected" : ""}>🏠 ${esc(r.name)}</option>`).join("")}
          <option value="none" ${rk === "none" ? "selected" : ""}>🏠 その他</option>
          ${ov ? `<option value="__ha__">Home Assistant のエリアに戻す</option>` : ""}</select></div>`;
    }
    list.innerHTML = html || `<div class="note">該当する機器がありません</div>`;
    $("#selTitle").textContent = `表示する機器（${all.filter((e) => sel.has(e.entity_id)).length}／${all.length}）`;
  }

  const HELP = [
    ["🏠 ホーム画面", [
      "上部に時計と天気（今日の最高・最低気温と数日分の予報）を表示します。",
      "左上に接続状態が表示されます。",
    ]],
    ["💡 機器の操作", [
      "スイッチ・シーンは、タイルをタップすると切り替え／実行します。",
      "照明・ファンは右上のスイッチでオン/オフ、スライダーで明るさ・風量を変えられます。",
      "エアコンはタイルで設定温度（−／＋）と運転モードを直接変えられます。",
      "タイルの名前の部分をタップすると詳細パネルが開きます（エアコンの風量・風向き、照明の色など）。",
    ]],
    ["🎤 音声で操作・質問", [
      "マイクボタンを押して話しかけます。話し終わると自動で止まります（■ボタンでも止められます）。",
      "例：「リビングの電気をつけて」「エアコンを26度にして」「近くのスーパーの営業時間は？」",
      "質問には AI が答えて読み上げ、答えは画面の下にポップアップで出ます（「✕ 閉じる」で閉じられます）。5分以内なら続けて質問できます。",
      "「近くの〜」「営業時間」など場所に関わる質問は、⚙ 設定 →「音声と AI」の「自宅の位置」を基準に答えます。",
      "「今日の予定は？」「明日の予定」と聞くと、カレンダーの予定を読み上げます。",
      SERVER_MODE ? "音声入力を使うには、iPad に専用サーバーの証明書を入れておく必要があります（README 参照）。" : "音声入力は https のアドレス（Home Assistant Cloud のリモートURL）で開いたときに使えます。",
    ]],
    ["🗣️ 呼びかけで反応", [
      "⚙ 設定 →「音声と AI」の「呼びかけで反応する」をオンにすると、呼びかけるだけで聞き取りを始めます（ピッと鳴ります）。",
      "この画面を表示している間だけ待ち受けます。iPad の「設定 → 画面表示と明るさ → 自動ロック」を「しない」にすると便利です。",
    ]],
    ["⚙ 表示する機器を選ぶ", [
      "⚙ 設定 →「部屋と機器」の「表示する機器」で、チェックを付けた機器だけを表示します。名前で絞り込めます。",
      "機器の右上の ⓘ で、いまの状態・最後に変わった時刻・直近24時間の動きを確認できます。",
    ]],
    ["👪 家族ごとの表示", [
      "⚙ 設定 →「ホーム画面の表示」で、家族それぞれのホーム画面（出す項目と並び順・配色）を作れます。",
      "2人以上作ると、上部に名前のボタンが出て、タップで切り替えられます。",
    ]],
    ["🌡 温度計", [
      "Home Assistant に取り込んだ温度計（SwitchBot 温湿度計など）の温度と湿度を表示します。タップすると温度と湿度の変化のグラフ（24時間／3日／7日）が出ます。",
      "⚙ 設定 →「温度計」の「✎ 名前」で、部屋の名前などに変えられます。",
    ]],
    ["📅 予定（TimeTree）", [
      "Home Assistant に取り込んだカレンダー（TimeTree など）の今日と明日の予定を表示します。表示するカレンダーは ⚙ 設定 →「カレンダー」で選びます。",
      "ホームパネルのサーバーが30分ごとに TimeTree から予定を取り出し、Home Assistant の「Remote Calendar」で読み込みます。TimeTree のログイン情報はサーバーの中にだけ保存し、このアプリには保存しません。",
    ]],
    ["⚡ 今日の電気", [
      "Home Assistant の「エネルギー」の設定から、今日の使用量・買電・売電・発電・電気代を表示します。",
    ]],
    ["📷 カメラ", [
      "Home Assistant のカメラを、ホーム画面のタイルにそのまま動画で表示します（⚙ 設定 →「カメラ」で静止画にも変えられます）。タップすると全画面になります。",
      "全画面の右上の「画質」で、自動／リアルタイム（WebRTC）／高画質（HLS）／簡易 を選べます。再生できないときは自動でほかの方式に切り替わります。",
    ]],
    ["⏱ タイマー", [
      "トップの「⏱ タイマー」から、いくつでも同時に設定できます。残り時間はホーム画面の上に表示されます。",
      "音声でも「5分のタイマー」「パスタの3分タイマー」「タイマーの残りは？」「タイマーを止めて」と言えます。",
      "時間になるとアラームが鳴り、「止める」ボタンが出ます（iPad の消音スイッチがオンだと音が出ないことがあります）。",
    ]],
    ["🖼 スクリーンセーバー", [
      "⚙ 設定 →「スクリーンセーバー」でオンにすると、操作していない時間が続いたときに写真と時計を表示します。",
      "Google フォトのアルバムを「共有」→「リンクを作成」して、そのリンクを貼り付けてください。画面に触れると元に戻ります。",
    ]],
    ["🚪 部屋ごとに分ける", [
      "機器は Home Assistant のエリアごとに分けて表示されます。上の「すべて／リビング／寝室…」で部屋を絞り込めます。",
      "⚙ 設定 →「部屋と機器」で、アプリだけの部屋を追加したり、▲▼ で部屋の並び順を変えたりできます。「表示する機器」の各機器の「🏠」で部屋を変えられます。",
    ]],
    ["⏰ スケジュール・オートメーション", [
      "トップの「⏰ 自動化」を開きます。",
      "スケジュール：「平日の7:00にエアコンを冷房にする」のように、時刻と曜日で自動実行します。",
      "オートメーション：「人感センサーが反応したら照明をつける」「日の入りの10分前にカーテンを閉める」のように、きっかけに合わせて動かします。",
      "Home Assistant に保存されるので、iPad を閉じていても実行されます。一覧のスイッチでオン/オフ、「今すぐ実行」で試せます。",
      "作成・編集には管理者の権限が必要です（下の「アカウント」）。",
    ]],
    ["👤 アカウント（管理者とその他）", [
      "普段は管理者以外のユーザーで使うのがおすすめです。",
      "自動化を作成・編集するときだけ、⚙ 設定 →「アカウント」→「管理者ログインを追加」で管理者としてもログインします。",
      "使い終わったら「管理者ログインを解除」できます。",
    ]],
    ["🎨 配色", ["⚙ 設定 →「ホーム画面の表示」の「配色」で、11種類の色の組み合わせから選べます。"]],
    ["💾 設定の保存", ["⚙ 設定 →「アプリの更新・設定の保存」で、設定をファイルに書き出し・読み込みできます（ログインの情報は含めません）。"]],
    ["📱 ホーム画面に追加", [
      "Safari でこのページを開き、共有ボタン（□↑）→「ホーム画面に追加」を選ぶと、アプリのように使えます。",
    ]],
    ["⬆️ アップデート", SERVER_MODE ? [
      "専用サーバーが5分おきに新しいバージョンを確認して、自動で入れ替えます。",
      "画面上部に「更新する」が出たら押してください。入れ替わり次第、自動で読み込み直します。",
    ] : [
      "新しいバージョンが出ると、画面上部に「更新する」ボタンが表示されます。押すと Home Assistant が自動で新しいファイルに入れ替えて、画面を読み込み直します。",
      "この機能には Home Assistant 側に shell_command.update_homepanel の設定が必要です（README 参照）。",
    ]],
    ["❄ エアコンの自動温度調整", [
      "ホームパネルのサーバーが、部屋の温度を決めた範囲に保つよう、エアコンを自動で冷房・暖房にします（iPad を閉じていても動きます）。",
      "上部の「❄ 自動調整」か ⚙ 設定 →「エアコンの自動温度調整」で、全体をすぐにオン/オフできます。「詳しく」で、いまの様子・24時間の温度・自動で行った操作を見られます。",
      "エアコンごとの温度の範囲は、Android 版の ⚙ 設定 →「エアコンの自動温度調整」で決めます。",
    ]],
    ["🛁 エコキュート（おひさま沸き上げ）", [
      "ホーム画面の電気のカードの下に、サーバーで管理しているエコキュートの残りのお湯と、おひさま沸き上げの様子が出ます。",
      "「夜は止める」方式のときは、晴れの日の夜に沸かす量（%・リットル）を選べます。直近7日の使ったお湯の量も表示します。",
    ]],
    ["🎞 カメラの記録と動きの分析", [
      "⚙ 設定 →「カメラの記録と動き」でカメラを選ぶと、サーバーが1分ごとに画像を保存し（2日分）、動きの量を記録します。",
      "「動きを見る」で48時間の動きのグラフと、寝た・起きた時刻の目安が分かります。棒をタップすると、その時刻の画像が出ます。",
    ]],
    ["📜 操作の記録", [
      "⚙ 設定 →「操作の記録」で、だれが・いつ・何を操作したか（Home Assistant の記録）と、サーバーが行った操作を見られます。",
    ]],
    ["💴 電気料金", [
      "Android 版の ⚙ 設定 →「電気料金」で電力会社とプランを選ぶと、サーバーが時間帯・季節・休日ごとの単価で電気代を計算し、ホーム画面に今日・今月・見込みの電気代を出します。",
    ]],
    ["⏲ 機器のタイマー", [
      "機器の詳細の画面（スイッチはⓘ）の「⏲ タイマー」で、「30分後〜4時間後」か時刻を指定して、切る・つける（エアコンは停止・冷房・暖房など）を予約できます。サーバーが動かすので、iPad を閉じていても動きます。",
    ]],
    ["↻ 画面の読み直し", [
      "ホーム画面のいちばん上で下に引っぱって離すと、機器の状態・電気・予定などを読み直します。",
    ]],
    ["🕘 時刻", [
      "時刻はすべて日本時間で表示し、1日の切り替わりも日本時間の0時です。",
    ]],
    ["❓ 困ったとき", [
      "接続できない：Home Assistant が動いているか、ネットワークにつながっているか確認してください。",
      "「呼びかけ検知が設定されていません」：Home Assistant の音声アシスタントでウェイクワードを設定してください。",
      "マイクが使えない：iPad の「設定 → アプリ → Safari → マイク」が許可になっているか確認してください。",
      "「この接続ではプライバシーが保護されません」と出る：iPad に専用サーバーの証明書（http://192.168.1.46/ca.crt）を入れ、「証明書信頼設定」でオンにしてください。",
      "呼びかけに反応しない：⚙ 設定 →「音声と AI」の「マイクのテスト」と「音声の動作ログ」で、音が入っているか・どこで止まっているかを確認できます。",
      "回答の読み上げ中でも、マイクボタンを押すと読み上げを止めて次の質問を聞きます。",
      "ログインし直したいとき：⚙ 設定 →「アカウント」→ ログアウト。",
    ]],
  ];
  function helpHTML() {
    return `<div class="wrap help"><div class="pagebar"><button class="tbtn" data-set="close">← 戻る</button><h2>使い方</h2></div>
      <input class="search" type="search" data-set="helpQuery" placeholder="🔍 言葉で探す（例：エコキュート、カメラ、ログイン）">
      <div id="helpList">${helpListHTML("")}</div>
      <div data-update-slot></div>
      <div style="text-align:center;margin:20px 0 40px"><button class="btn" data-set="close">閉じる</button></div></div>`;
  }
  function helpListHTML(q) {
    q = (q || "").trim().toLowerCase();
    const list = HELP.map(([t, items], i) => [i, t, q && !t.toLowerCase().includes(q) ? items.filter((x) => x.toLowerCase().includes(q)) : items]).filter(([, , items]) => items.length);
    return `<div class="card"><h4>${q ? `見つかった項目：${list.length}件` : "目次（タップでその項目へ）"}</h4>
        ${list.length ? `<div class="chips">${list.map(([i, t]) => `<button class="chip" data-set="helpGo" data-id="${i}">${esc(t)}</button>`).join("")}</div>` : `<div class="note">見つかりませんでした</div>`}</div>
      ${list.map(([i, t, items]) => `<div class="card" id="help-${i}"><div class="brow" style="align-items:center"><h4 style="flex:1;margin:0">${esc(t)}</h4><button class="tbtn" data-set="helpTop">↑ 目次</button></div>
        <ul>${items.map((x) => `<li>${esc(x)}</li>`).join("")}</ul></div>`).join("")}`;
  }
  function renderHelpList(q) { const b = $("#helpList"); if (b) b.innerHTML = helpListHTML(q); }

  function openPage(name) {
    S.page = name;
    const page = $("#page");
    page.classList.remove("hidden");
    page.scrollTop = 0;
    if (name === "auto") { S.edit = null; S.autoMsg = null; renderAuto(); loadRules(); return; }
    if (name === "timers") { page.innerHTML = timersHTML(); renderTimers(); return; }
    page.innerHTML = name === "settings" ? settingsHTML() : helpHTML();
    if (name === "settings") { renderSelList(); fillExtra(); afterSettingsOpen(); }
    renderUpdate();
    if (name === "settings" && !S.settingsPage) page.scrollTop = 0;
  }
  /** 設定ページを開いたまま描き直す（スクロール位置は保つ） */
  function openPageKeep() {
    if (S.page !== "settings") return;
    const page = $("#page");
    withKeptFocus(page, () => {
      page.innerHTML = settingsHTML();
      renderSelList();
      fillExtra();
      renderUpdate();
    });
  }
  function closePage() {
    if (S.page === "help") store.set("helpShown", true);
    S.page = null;
    S.settingsPage = null;
    S.edit = null;
    $("#page").classList.add("hidden");
    $("#page").innerHTML = "";
  }

  async function onSetting(el) {
    if (await onNewSetting(el)) return;
    if (await onExtraSetting(el)) return;
    if (S.page === "auto" && el.dataset.set !== "close" && (await onAutoSetting(el))) return;
    switch (el.dataset.set) {
      case "theme": S.theme = el.dataset.id; store.set("theme", S.theme); updateProfile(activeProfile().id, (p) => Object.assign(p, { theme: S.theme })); applyTheme(); openPageKeep(); break;
      case "roomFilter": S.roomFilter = el.dataset.id || null; store.set("roomFilter", S.roomFilter); renderEntities(); break;
      case "groupRooms": S.groupRooms = el.checked; store.set("groupRooms", S.groupRooms); renderEntities(); break;
      case "roomAdd": {
        const inp = $("#newRoom");
        const n = inp && inp.value.trim();
        if (!n) break;
        S.customRooms["local:" + Date.now()] = n;
        saveRooms(); openPageKeep(); renderEntities();
        break;
      }
      case "roomRename": {
        const n = prompt("部屋の名前", S.customRooms[el.dataset.id] || "");
        if (n && n.trim()) { S.customRooms[el.dataset.id] = n.trim(); saveRooms(); openPageKeep(); renderEntities(); }
        break;
      }
      case "roomDel":
        if (!confirm(`「${S.customRooms[el.dataset.id]}」を削除しますか？（機器は「その他」などに戻ります）`)) break;
        delete S.customRooms[el.dataset.id];
        Object.keys(S.roomOv).forEach((k) => { if (S.roomOv[k] === el.dataset.id) delete S.roomOv[k]; });
        saveRooms(); openPageKeep(); renderEntities();
        break;
      case "room":
        if (el.value === "__ha__") delete S.roomOv[el.dataset.id]; else S.roomOv[el.dataset.id] = el.value;
        saveRooms(); renderEntities();
        break;
      case "adminLogin": startLogin("admin"); break;
      case "adminLogout":
        store.del("admin_tokens"); store.del("admin_user"); S.rules = {};
        openPageKeep();
        break;
      case "close": closePage(); break;
      case "wake":
        if (el.checked) {
          unlockAudio();
          try { await mic.ensure(); } catch (e) { el.checked = false; alert(e.message); return; }
        }
        S.wake = el.checked;
        store.set("wake", S.wake);
        if (S.wake) kickWake(); else { await abortWake(); if (!S.busy) mic.stop(); setVoice({ type: "idle" }); }
        break;
      case "pipeline":
        S.pipelineId = el.dataset.id;
        store.set("pipeline", S.pipelineId);
        await abortWake();
        kickWake();
        break;
      case "weather":
        S.weatherId = el.dataset.id;
        store.set("weather", S.weatherId);
        renderWeather();
        break;
      case "sel": {
        const set = selectedSet();
        if (el.checked) set.add(el.dataset.id); else set.delete(el.dataset.id);
        saveSelection(set);
        renderSelList();
        renderEntities();
        break;
      }
      case "all": saveSelection(new Set(allEntities().map((e) => e.entity_id))); renderSelList(); renderEntities(); break;
      case "none": saveSelection(new Set()); renderSelList(); renderEntities(); break;
      case "logout": if (confirm("ログアウトしますか？\nもう一度使うにはログインが必要です。")) { store.del("admin_tokens"); store.del("admin_user"); logout(); } break;
      case "checkUpdate": checkUpdate(false); break;
      case "doUpdate": doUpdate(); break;
    }
  }

  // ================= 配色 =================
  const THEMES = [
    { id: "orange", name: "オレンジ", colors: ["#ffb74d", "#5d4020", "#2b2930"], bg: "#141218", fg: "#e6e1e5" },
    { id: "blue", name: "ブルー", colors: ["#90caf9", "#1e3a5f", "#1f2a33"], bg: "#0f1418", fg: "#e1e6ec" },
    { id: "green", name: "グリーン", colors: ["#a5d6a7", "#1f4d2b", "#1f2b22"], bg: "#0f1511", fg: "#e0e6e0" },
    { id: "purple", name: "パープル", colors: ["#ce93d8", "#4a2655", "#2a2430"], bg: "#151218", fg: "#e8e0ea" },
    { id: "red", name: "レッド", colors: ["#ef9a9a", "#5c2222", "#302424"], bg: "#181212", fg: "#ede0de" },
    { id: "teal", name: "ティール", colors: ["#80cbc4", "#1c4a47", "#1d2b2a"], bg: "#0e1515", fg: "#dfe6e5" },
    { id: "mono", name: "モノクロ", colors: ["#e0e0e0", "#424242", "#262626"], bg: "#101010", fg: "#e6e6e6" },
    { id: "night", name: "ナイト（暗め）", colors: ["#b0855a", "#3a2817", "#141210"], bg: "#000000", fg: "#a89f96" },
    { id: "light_orange", name: "ライト・オレンジ", colors: ["#e65100", "#ffe0b2", "#f3e9e0"], bg: "#fff8f3", fg: "#1f1b16" },
    { id: "light_blue", name: "ライト・ブルー", colors: ["#1565c0", "#d6e8ff", "#e3eaf2"], bg: "#f7f9fc", fg: "#191c20" },
    { id: "light_green", name: "ライト・グリーン", colors: ["#2e7d32", "#d7f5d8", "#e2ede2"], bg: "#f6fbf5", fg: "#191d19" },
  ];

  function applyTheme() {
    document.documentElement.dataset.theme = S.theme;
    const t = THEMES.find((x) => x.id === S.theme) || THEMES[0];
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", t.bg);
    document.body.style.background = t.bg;
  }
  function themesHTML() {
    return `<div class="themes">${THEMES.map((t) => `<button class="theme${t.id === S.theme ? " sel" : ""}" data-set="theme" data-id="${t.id}"
      style="background:${t.bg};color:${t.fg}"><span class="dots">${t.colors.map((c) => `<span style="background:${c}"></span>`).join("")}</span>
      ${t.id === S.theme ? "✓ " : ""}${esc(t.name)}</button>`).join("")}</div>`;
  }

  // ================= 部屋 =================
  const NO_ROOM = "none";
  function rooms() {
    const list = S.haAreas.concat(Object.entries(S.customRooms).sort((a, b) => a[0].localeCompare(b[0])).map(([key, name]) => ({ key, name, ha: false })));
    const order = store.get("roomOrder", []);
    const at = (k) => { const i = order.indexOf(k); return i < 0 ? 1e9 : i; };
    return list.map((r, i) => [r, i]).sort((a, b) => at(a[0].key) - at(b[0].key) || a[1] - b[1]).map(([r]) => r);
  }
  function roomKey(id) {
    const rs = rooms();
    const o = S.roomOv[id];
    if (o && (o === NO_ROOM || rs.some((r) => r.key === o))) return o;
    const a = S.entityAreas[id];
    return a && rs.some((r) => r.key === "area:" + a) ? "area:" + a : NO_ROOM;
  }
  function roomName(key) { const r = rooms().find((x) => x.key === key); return r ? r.name : "その他"; }
  function groupedByRoom(list) {
    const by = {};
    list.forEach((e) => { const k = roomKey(e.entity_id); (by[k] = by[k] || []).push(e); });
    return rooms().concat([{ key: NO_ROOM, name: "その他" }]).filter((r) => by[r.key] && by[r.key].length).map((r) => [r, by[r.key]]);
  }
  async function loadRegistries() {
    try {
      const ha = S.ha;
      const areas = await ha.send({ type: "config/area_registry/list" });
      S.haAreas = areas.map((a) => ({ key: "area:" + a.area_id, name: a.name, ha: true })).sort((a, b) => a.name.localeCompare(b.name, "ja"));
      const devs = await ha.send({ type: "config/device_registry/list" });
      const devArea = {};
      const devNames = {};
      devs.forEach((d) => { if (d.area_id) devArea[d.id] = d.area_id; devNames[d.id] = d.name_by_user || d.name || ""; });
      S.deviceNames = devNames;
      const ents = await ha.send({ type: "config/entity_registry/list" });
      const map = {};
      const entDev = {};
      ents.forEach((e) => { const a = e.area_id || (e.device_id && devArea[e.device_id]); if (a) map[e.entity_id] = a; if (e.device_id) entDev[e.entity_id] = e.device_id; });
      S.entityAreas = map;
      S.entityDevice = entDev;
      renderEntities();
      renderThermos();
    } catch (e) { /* エリアを読めなくても、アプリで追加した部屋は使える */ }
  }
  function saveRooms() {
    store.set("rooms", S.customRooms);
    store.set("roomOv", S.roomOv);
  }
  function renderRoomBar() {
    const bar = $("#roombar");
    if (!bar) return;
    const present = groupedByRoom(shownEntitiesAll()).map(([r]) => r);
    if (present.length <= 1) { bar.innerHTML = ""; return; }
    const f = present.some((r) => r.key === S.roomFilter) ? S.roomFilter : null;
    bar.innerHTML = `<button class="chip${f === null ? " sel" : ""}" data-set="roomFilter" data-id="">すべて</button>` +
      present.map((r) => `<button class="chip${f === r.key ? " sel" : ""}" data-set="roomFilter" data-id="${esc(r.key)}">${esc(r.name)}</button>`).join("");
    // UI-012：選んでいる部屋が見えるところまで、横にスクロールする
    const selb = bar.querySelector(".chip.sel");
    if (selb && bar.scrollWidth > bar.clientWidth) bar.scrollLeft = Math.max(0, selb.offsetLeft - 16);
  }

  // ================= アカウント（普段使い／管理者） =================
  async function loadUser() {
    try {
      const u = await S.ha.send({ type: "auth/current_user" });
      S.user = { name: u.name, isAdmin: !!u.is_admin };
    } catch (e) { /* 不明のまま */ }
  }
  const canEditRules = () => (S.user && S.user.isAdmin) || !!store.get("admin_tokens");

  async function editorToken() {
    const t = store.get("admin_tokens");
    if (!t) return accessToken();
    if (t.access && t.expires - 60000 > Date.now()) return t.access;
    try {
      const r = await tokenRequest({ grant_type: "refresh_token", refresh_token: t.refresh, client_id: CLIENT_ID });
      t.access = r.access_token;
      t.expires = Date.now() + r.expires_in * 1000;
      store.set("admin_tokens", t);
      return t.access;
    } catch (e) {
      if (e instanceof RevokedError) { store.del("admin_tokens"); store.del("admin_user"); throw new Error("管理者ログインの期限が切れました。設定 → アカウント でもう一度ログインしてください"); }
      throw e;
    }
  }

  async function rest(method, path, body) {
    const token = await editorToken();
    const r = await fetch(path, {
      method,
      headers: Object.assign({ Authorization: "Bearer " + token }, body ? { "Content-Type": "application/json" } : {}),
      body: body ? JSON.stringify(body) : undefined,
    });
    if (r.status === 401) throw new Error("自動化の編集には管理者の権限が必要です（設定 → アカウント で管理者ログインを追加してください）");
    if (!r.ok) throw new Error("Home Assistant でエラーになりました (HTTP " + r.status + ")");
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  }

  /** 管理者ログインの結果を受け取る（Home Assistant のログイン画面から戻ってきたとき） */
  async function finishAdminLogin(t) {
    const r = await fetch("/api/config/automation/config/__homepanel_check__", { headers: { Authorization: "Bearer " + t.access_token } });
    if (r.status === 401) throw new Error("ログインしたユーザーは管理者ではありません。Home Assistant の管理者ユーザーでログインしてください");
    let name = "管理者";
    try {
      const ha = new HA();
      await ha.connect(t.access_token);
      const u = await ha.send({ type: "auth/current_user" });
      name = u.name;
      ha.close();
    } catch (e) { /* 名前は分からなくても使える */ }
    store.set("admin_tokens", { access: t.access_token, refresh: t.refresh_token, expires: Date.now() + t.expires_in * 1000 });
    store.set("admin_user", name);
  }

  function accountHTML() {
    const u = S.user;
    const adminUser = store.get("admin_user");
    let h = `<div class="note" style="font-size:16px;color:var(--on)">普段使いのユーザー：${esc(u ? u.name : "（確認中）")}${u ? (u.isAdmin ? "（管理者）" : "（管理者以外）") : ""}</div>`;
    if (u && u.isAdmin) {
      h += `<div class="note">このユーザーは管理者なので、オートメーション・スケジュールの作成や編集もできます。</div>`;
    } else if (adminUser && store.get("admin_tokens")) {
      h += `<div class="note" style="font-size:16px;color:var(--on)">管理者ログイン（自動化の編集用）：${esc(adminUser)}</div>
        <div class="note">機器の操作は普段使いのユーザーで行い、オートメーション・スケジュールの編集だけ管理者の権限を使います。</div>
        <div><button class="btn outline" data-set="adminLogout">管理者ログインを解除</button></div>`;
    } else {
      h += `<div class="note">オートメーション・スケジュールを作成・編集するには、管理者ユーザーでのログインを追加してください。普段の操作はこのまま管理者以外のユーザーで行えます。<br>
        ボタンを押すと Home Assistant のログイン画面が開くので、<b>管理者のユーザー名</b>でログインしてください。</div>
        <div><button class="btn" data-set="adminLogin">管理者ログインを追加</button></div>`;
    }
    return h + `<div style="margin-top:14px"><button class="btn outline" data-set="logout">ログアウト（すべて）</button></div>`;
  }

  // ================= オートメーション・スケジュール =================
  const WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
  const WD = { mon: "月", tue: "火", wed: "水", thu: "木", fri: "金", sat: "土", sun: "日" };
  const TRIGGER_DOMAINS = ["binary_sensor", "person", "device_tracker", "light", "switch", "fan", "cover", "climate", "input_boolean", "lock"];
  const isAppRule = (id) => String(id).startsWith("hp_");
  const isSched = (id) => String(id).startsWith("hp_sched_");
  const pad = (n) => String(n).padStart(2, "0");
  const hhmm = (s) => { const p = String(s).split(":"); return p.length >= 2 ? pad(+p[0] || 0) + ":" + pad(+p[1] || 0) : s; };

  function newRule(schedule) {
    return { id: (schedule ? "hp_sched_" : "hp_auto_") + Date.now(), name: "", schedule, trigger: { kind: "time", at: "07:00" }, weekdays: [], after: null, before: null, actions: [], conds: [], isNew: true };
  }

  function opsFor(d) {
    switch (d) {
      case "light": return [["turn_on", "つける"], ["turn_on", "明るさを指定してつける", "brightness_pct"], ["turn_off", "消す"], ["toggle", "切り替える"]];
      case "switch": case "input_boolean": case "fan": return [["turn_on", "オンにする"], ["turn_off", "オフにする"], ["toggle", "切り替える"]];
      case "climate": return [["set_temperature", "運転する（モードと温度）", "mode_temp"], ["set_hvac_mode", "運転モードだけ変える", "hvac_mode"], ["set_temperature", "温度だけ変える", "temperature"], ["set_fan_mode", "風量だけ変える", "fan_mode"], ["turn_off", "停止する"]];
      case "cover": return [["open_cover", "開ける"], ["close_cover", "閉める"], ["set_cover_position", "開き具合を指定する", "position"]];
      case "scene": case "script": return [["turn_on", "実行する"]];
      default: return [["turn_on", "オンにする"], ["turn_off", "オフにする"]];
    }
  }
  const PARAM_DEFAULT = { brightness_pct: 100, temperature: 25, hvac_mode: "cool", position: 50, fan_mode: "auto" };
  function opIndex(a) {
    const d = a.entity.split(".")[0], svc = a.service.split(".")[1];
    const ops = opsFor(d);
    if (d === "climate" && svc === "set_temperature") return ops.findIndex((o) => o[2] === (a.data.hvac_mode !== undefined ? "mode_temp" : "temperature"));
    let i = ops.findIndex((o) => o[0] === svc && o[2] && a.data[o[2]] !== undefined);
    if (i < 0) i = ops.findIndex((o) => o[0] === svc && !o[2]);
    if (i < 0) i = ops.findIndex((o) => o[0] === svc);
    return i;
  }
  function makeAct(entity, op) {
    const d = entity.split(".")[0];
    if (op[2] === "mode_temp") return { entity, service: d + "." + op[0], data: { hvac_mode: "cool", temperature: 25 } }; // 初めは冷房 25℃
    return { entity, service: d + "." + op[0], data: op[2] ? { [op[2]]: PARAM_DEFAULT[op[2]] } : {} };
  }
  function stateOptions(d) {
    switch (d) {
      case "binary_sensor": return [["on", "反応した"], ["off", "反応しなくなった"]];
      case "person": case "device_tracker": return [["home", "帰宅した"], ["not_home", "外出した"]];
      case "cover": return [["open", "開いた"], ["closed", "閉まった"]];
      case "lock": return [["locked", "施錠した"], ["unlocked", "解錠した"]];
      case "climate": return [["off", "停止した"], ["cool", "冷房になった"], ["heat", "暖房になった"], ["dry", "除湿になった"]];
      default: return [["on", "オンになった"], ["off", "オフになった"]];
    }
  }
  /** 条件で選べる状態（「〜している」の形） */
  function condStateOptions(d) {
    switch (d) {
      case "binary_sensor": return [["on", "反応している"], ["off", "反応していない"]];
      case "person": case "device_tracker": return [["home", "家にいる"], ["not_home", "外出している"]];
      case "cover": return [["open", "開いている"], ["closed", "閉まっている"]];
      case "lock": return [["locked", "施錠している"], ["unlocked", "解錠している"]];
      case "climate": return [["off", "停止している"], ["cool", "冷房している"], ["heat", "暖房している"], ["dry", "除湿している"]];
      default: return [["on", "オンになっている"], ["off", "オフになっている"]];
    }
  }
  function ruleCondLabel(c) {
    if (c.kind === "numeric") return `${numName(c.entity)} が${c.value}${numIsHum(c.entity) ? "%" : "℃"}${c.above ? "より高い" : "より低い"}`;
    const o = condStateOptions(c.entity.split(".")[0]).find((x) => x[0] === c.state);
    return `${entName(c.entity)} が${o ? o[1] : `「${c.state}」`}`;
  }
  /** サーバーに送られた ECHONET Lite の機器（Android 版が見つけたもの。Home Assistant には無い） */
  const elDevs = () => ((S.solar && S.solar.config && S.solar.config.el_devices) || []);
  const isElId = (id) => /\.el_\d+_\d+_\d+_\d+_[0-9a-f]{6}$/.test(id || "");
  function withRoom(room, name) { return !room || name.includes(room) ? name : `${room}・${name}`; }
  function entRoom(id) {
    const ed = elDevs().find((x) => x.id === id);
    if (ed) return ed.room || null;
    const k = roomKey(id);
    return k === NO_ROOM ? null : roomName(k);
  }
  const entName = (id) => {
    const ed = elDevs().find((x) => x.id === id);
    const n = S.ha && S.ha.states[id] ? nameOf(S.ha.states[id]) : ed ? ed.name : id;
    return withRoom(entRoom(id), n);
  };
  const DOMAIN_LABEL2 = Object.assign({}, DOMAIN_LABEL, { binary_sensor: "センサー", person: "人", device_tracker: "位置", lock: "鍵" });

  function actLabel(a) {
    const d = a.entity.split(".")[0];
    const op = opsFor(d)[opIndex(a)];
    const n = entName(a.entity);
    if (op && op[2] === "brightness_pct") return `${n} を明るさ ${a.data.brightness_pct}% でつける`;
    if (op && op[2] === "temperature") return `${n} を ${a.data.temperature}℃ に設定する`;
    if (op && op[2] === "hvac_mode") return `${n} を${hvacLabel(a.data.hvac_mode)}にする${a.data.fan_mode ? `（風量 ${fanLabel(a.data.fan_mode)}）` : ""}`;
    if (op && op[2] === "mode_temp") return `${n} を${hvacLabel(a.data.hvac_mode)}${["fan_only", "dry"].includes(a.data.hvac_mode) ? "" : " " + a.data.temperature + "℃"}${a.data.fan_mode ? "・風量 " + fanLabel(a.data.fan_mode) : ""}で運転する`;
    if (op && op[2] === "fan_mode") return `${n} の風量を${fanLabel(a.data.fan_mode)}にする`;
    if (op && op[2] === "position") return `${n} を ${a.data.position}% 開ける`;
    return `${n} を${op ? op[1] : a.service}`;
  }
  function weekdayText(w) {
    if (!w.length || w.length === 7) return "毎日";
    const s = WEEKDAYS.filter((d) => w.includes(d));
    if (s.join() === "mon,tue,wed,thu,fri") return "平日";
    if (s.join() === "sat,sun") return "土日";
    return s.map((d) => WD[d]).join("・");
  }
  // 温度計のきっかけ：アプリで付けた名前で「寝室の温度」と出す
  const numIsHum = (id) => thermos().some((th) => th.hId === id) || /humid/.test(id);
  function numName(id) {
    const th = thermos().find((x) => x.tId === id || x.hId === id);
    if (th) return th.name + (th.hId === id ? "の湿度" : "の温度");
    const e = S.ha && S.ha.states[id];
    return e ? nameOf(e) : id;
  }
  function trigLabel(t) {
    if (t.kind === "time") return `${t.at.replace(/^0(\d)/, "$1")} に`;
    if (t.kind === "state") {
      const o = stateOptions(t.entity.split(".")[0]).find((x) => x[0] === t.to);
      return `${entName(t.entity)} が${o ? o[1] : `「${t.to}」になった`}とき`;
    }
    if (t.kind === "numeric") {
      const u = numIsHum(t.entity) ? "%" : "℃";
      return `${numName(t.entity)} が${t.value}${u}${t.above ? "を超えた" : "を下回った"}とき${t.forMin ? `（${t.forMin}分続いたら）` : ""}`;
    }
    const base = t.event === "sunrise" ? "日の出" : "日の入り";
    return base + (t.offset < 0 ? `の${-t.offset}分前に` : t.offset > 0 ? `の${t.offset}分後に` : "に");
  }
  function ruleSummary(r) {
    const time = [weekdayText(r.weekdays), r.after || r.before ? `${r.after || "0:00"}〜${r.before || "24:00"} の間` : null].filter(Boolean).join("・");
    const cond = (r.conds || []).length ? "（" + r.conds.map(ruleCondLabel).join("、かつ") + "なら）" : "";
    return `${time} ${trigLabel(r.trigger)}${cond} → ${r.actions.map(actLabel).join("、") || "（操作なし）"}`;
  }

  function toConfig(r) {
    const t = r.trigger;
    let trig;
    if (t.kind === "time") trig = { trigger: "time", at: hhmm(t.at) + ":00" };
    else if (t.kind === "state") trig = { trigger: "state", entity_id: t.entity, to: t.to };
    else if (t.kind === "numeric") {
      trig = { trigger: "numeric_state", entity_id: t.entity };
      trig[t.above ? "above" : "below"] = t.value;
      if (t.forMin) trig.for = `${pad(Math.floor(t.forMin / 60))}:${pad(t.forMin % 60)}:00`;
    } else {
      trig = { trigger: "sun", event: t.event };
      if (t.offset) trig.offset = (t.offset < 0 ? "-" : "") + `${pad(Math.floor(Math.abs(t.offset) / 60))}:${pad(Math.abs(t.offset) % 60)}:00`;
    }
    const conds = [];
    if (r.weekdays.length || r.after || r.before) {
      const c = { condition: "time" };
      if (r.weekdays.length) c.weekday = WEEKDAYS.filter((d) => r.weekdays.includes(d));
      if (r.after) c.after = hhmm(r.after) + ":00";
      if (r.before) c.before = hhmm(r.before) + ":00";
      conds.push(c);
    }
    (r.conds || []).forEach((c) => {
      if (c.kind === "numeric") { const o = { condition: "numeric_state", entity_id: c.entity }; o[c.above ? "above" : "below"] = c.value; conds.push(o); }
      else conds.push({ condition: "state", entity_id: c.entity, state: c.state });
    });
    return {
      id: r.id, alias: r.name, description: "ホームパネルで作成", mode: "single",
      triggers: [trig], conditions: conds,
      actions: r.actions.map((a) => {
        const data = Object.assign({}, a.data);
        if (a.service === "climate.set_temperature" && ["fan_only", "dry"].includes(data.hvac_mode)) delete data.temperature;
        if (isElId(a.entity)) {
          const ed = elDevs().find((x) => x.id === a.entity);
          return { event: "homepanel_el", event_data: { entity_id: a.entity, service: a.service.split(".")[1], data, name: ed ? withRoom(ed.room, ed.name) : a.entity } };
        }
        // 風量は Home Assistant では別の操作（climate.set_fan_mode）なので、続けて送る
        let fan = null;
        if (a.service.startsWith("climate.") && a.service !== "climate.set_fan_mode" && data.fan_mode !== undefined) { fan = data.fan_mode; delete data.fan_mode; }
        let main;
        if (a.service === "climate.set_temperature" && data.temperature !== undefined && data.hvac_mode && data.hvac_mode !== "off") {
          // 運転モードと温度は別々に送る（温度の操作に運転モードを付けても、運転を入れない連携があるため）
          const t = Object.assign({}, data); delete t.hvac_mode;
          main = [{ action: "climate.set_hvac_mode", target: { entity_id: a.entity }, data: { hvac_mode: data.hvac_mode } },
            { action: "climate.set_temperature", target: { entity_id: a.entity }, data: t }];
        } else {
          main = [a.service === "climate.set_temperature" && data.temperature === undefined && data.hvac_mode
            ? { action: "climate.set_hvac_mode", target: { entity_id: a.entity }, data } : { action: a.service, target: { entity_id: a.entity }, data }];
        }
        return fan === null ? main : [...main, { action: "climate.set_fan_mode", target: { entity_id: a.entity }, data: { fan_mode: fan } }];
      }).flat(),
    };
  }

  const asList = (v) => (Array.isArray(v) ? v : v ? [v] : null);
  const single = (v) => (typeof v === "string" ? v : Array.isArray(v) && v.length === 1 ? v[0] : null);
  function parseOffset(v) {
    if (v === undefined || v === null) return 0;
    if (typeof v === "number") return Math.round(v / 60);
    if (typeof v === "object") return (v.hours || 0) * 60 + (v.minutes || 0);
    const neg = String(v).startsWith("-");
    const p = String(v).replace(/^[-+]/, "").split(":").map(Number);
    const m = p.length >= 2 ? p[0] * 60 + p[1] : Math.round(p[0] / 60);
    return neg ? -m : m;
  }
  /** Home Assistant の自動化設定を読み込む。アプリで扱えない形なら null */
  function parseRule(id, j) {
    const trigs = asList(j.triggers || j.trigger);
    if (!trigs || trigs.length !== 1) return null;
    const t = trigs[0], kind = t.trigger || t.platform;
    let trigger;
    if (kind === "time") { const at = single(t.at); if (!at) return null; trigger = { kind: "time", at: hhmm(at) }; }
    else if (kind === "state") { const e = single(t.entity_id); if (!e || t.to === undefined) return null; trigger = { kind: "state", entity: e, to: String(t.to) }; }
    else if (kind === "sun") trigger = { kind: "sun", event: t.event || "sunset", offset: parseOffset(t.offset) };
    else if (kind === "numeric_state") {
      const e = single(t.entity_id), hasA = t.above !== undefined, hasB = t.below !== undefined;
      const v = Number(hasA ? t.above : t.below);
      if (!e || hasA === hasB || isNaN(v)) return null;
      trigger = { kind: "numeric", entity: e, above: hasA, value: v, forMin: Math.max(0, parseOffset(t.for)) };
    } else return null;
    const conds = asList(j.conditions || j.condition) || [];
    let weekdays = [], after = null, before = null, timeSeen = false;
    const extra = [];
    for (const c of conds) {
      if (c.condition === "time") {
        if (timeSeen) return null;
        timeSeen = true;
        weekdays = asList(c.weekday) || [];
        after = c.after ? hhmm(c.after) : null;
        before = c.before ? hhmm(c.before) : null;
      } else if (c.condition === "numeric_state") {
        const e = single(c.entity_id), hasA = c.above !== undefined, hasB = c.below !== undefined, v = Number(hasA ? c.above : c.below);
        if (!e || hasA === hasB || isNaN(v)) return null;
        extra.push({ kind: "numeric", entity: e, above: hasA, value: v });
      } else if (c.condition === "state") {
        const e = single(c.entity_id), st = single(c.state);
        if (!e || st === null) return null;
        extra.push({ kind: "state", entity: e, state: String(st) });
      } else return null;
    }
    const actsRaw = asList(j.actions || j.action);
    if (!actsRaw) return null;
    // Android 版が付ける「利用不可なら直接操作」の分かれ道は、Home Assistant 側の操作だけを取り出す
    const acts = [];
    for (const a of actsRaw) {
      if (a && a.choose && a.alias === "ホームパネル：利用不可なら直接操作") {
        const seq = a.choose[0] && a.choose[0].sequence;
        if (!Array.isArray(seq)) return null;
        acts.push(...seq);
      } else acts.push(a);
    }
    const actions = [];
    for (const a of acts) {
      if (a.event === "homepanel_el") {
        const ed = a.event_data || {};
        if (!isElId(ed.entity_id)) return null;
        actions.push({ entity: ed.entity_id, service: ed.entity_id.split(".")[0] + "." + ed.service, data: Object.assign({}, ed.data || {}) });
        continue;
      }
      const svc = a.action || a.service;
      if (!svc || !svc.includes(".")) return null;
      const data = Object.assign({}, a.data || a.service_data || {});
      const entity = single(a.target && a.target.entity_id) || single(data.entity_id) || single(a.entity_id);
      if (!entity) return null;
      delete data.entity_id;
      const last = actions[actions.length - 1];
      // 運転モード → 温度 の2つは、1つの「運転モードと温度」にまとめて読む
      if (svc === "climate.set_temperature" && last && last.entity === entity && last.service === "climate.set_hvac_mode" &&
          last.data.temperature === undefined && data.temperature !== undefined && data.hvac_mode === undefined) {
        last.service = "climate.set_temperature";
        Object.assign(last.data, data);
        continue;
      }
      if (svc === "climate.set_fan_mode" && last && last.entity === entity && ["climate.set_temperature", "climate.set_hvac_mode"].includes(last.service) && last.data.fan_mode === undefined && data.fan_mode !== undefined) {
        last.data.fan_mode = data.fan_mode;
        continue;
      }
      actions.push({ entity, service: svc, data });
    }
    return { id, name: j.alias || "", schedule: isSched(id), trigger, weekdays, after, before, actions, conds: extra };
  }

  function automations() {
    return S.ha ? Object.values(S.ha.states).filter((e) => e.entity_id.startsWith("automation.")).sort((a, b) => nameOf(a).localeCompare(nameOf(b), "ja")) : [];
  }

  async function loadRules() {
    if (!S.haTz && S.ha && S.ha.send) S.ha.send({ type: "get_config" }).then((r) => { S.haTz = (r && (r.result || r).time_zone) || null; renderAuto(); }).catch(() => {});
    if (!canEditRules()) return;
    S.rulesLoading = true;
    renderAuto();
    try {
      const map = {};
      for (const a of automations()) {
        const id = a.attributes.id;
        if (!isAppRule(id)) continue;
        try { map[id] = parseRule(id, await rest("GET", "/api/config/automation/config/" + encodeURIComponent(id))); }
        catch (e) { if (/権限/.test(e.message)) throw e; map[id] = null; }
      }
      S.rules = map;
      S.autoMsg = null;
    } catch (e) {
      S.autoMsg = e.message;
    } finally {
      S.rulesLoading = false;
      renderAuto();
    }
  }

  function autoListHTML() {
    const tab = S.autoTab;
    const list = automations().filter((a) => (tab === 0 ? isSched(a.attributes.id) : !isSched(a.attributes.id)));
    const can = canEditRules();
    return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="close">← 戻る</button><h2>自動化</h2>${S.rulesLoading ? `<span class="note">読み込み中…</span>` : ""}</div>
      <div class="tabs"><button class="tab${tab === 0 ? " sel" : ""}" data-set="autoTab" data-id="0">⏰ スケジュール</button>
        <button class="tab${tab === 1 ? " sel" : ""}" data-set="autoTab" data-id="1">⚡ オートメーション</button></div>
      <div class="note" style="font-size:15px;color:var(--on)">${tab === 0 ? "決まった時刻・曜日に機器を自動で操作します（例：平日の7時にエアコンをつける）。"
        : "「人感センサーが反応したら照明をつける」「日の入りにカーテンを閉める」のように、きっかけに合わせて自動で操作します。"}</div>
      <div class="note">設定は Home Assistant に保存されるので、この iPad を閉じていても実行されます。</div>
      ${S.haTz && S.haTz !== "Asia/Tokyo" ? `<div class="warn">Home Assistant の時間帯が「${esc(S.haTz)}」になっています。スケジュールの時刻がずれるので、Home Assistant の「設定 → システム → 一般」で時間帯を「Asia/Tokyo」にしてください。</div>` : ""}
      ${can ? "" : `<div class="info-box">作成・編集には管理者の権限が必要です。⚙ 設定 → アカウント で「管理者ログインを追加」してください。オン/オフと今すぐ実行は、このままでも使えます。</div>`}
      ${S.autoMsg ? `<div class="info-box ${/しました$/.test(S.autoMsg) ? "ok-box" : /できません|ません|失敗|エラー/.test(S.autoMsg) ? "err-box" : ""}" role="status" aria-live="polite">${esc(S.autoMsg)}</div>` : ""}
      <div style="margin:12px 0"><button class="btn" data-set="newRule" ${can ? "" : "disabled"}>${tab === 0 ? "＋ 新しいスケジュール" : "＋ 新しいオートメーション"}</button></div>
      ${list.length ? "" : `<div class="note" style="padding:16px">${tab === 0 ? "スケジュール" : "オートメーション"}はまだありません。</div>`}
      ${list.map((a) => {
        const id = a.attributes.id, rule = S.rules[id];
        const sum = rule ? ruleSummary(rule) : isAppRule(id) ? "（内容を読み込み中、または読み込めません）" : "Home Assistant で作成";
        const last = a.attributes.last_triggered ? "最後に実行：" + (isoLocal(String(a.attributes.last_triggered)) || String(a.attributes.last_triggered)) : "";
        return `<div class="auto" data-set="editRule" data-id="${esc(a.entity_id)}" aria-label="${esc(nameOf(a))}を編集"><div class="at"><div class="an">${esc(nameOf(a))}${isAppRule(id) && canEditRules() ? ' <span class="edhint">✎ 編集</span>' : ""}</div>
          <div class="as">${esc(sum)}</div>${last ? `<div class="as">${esc(last)}</div>` : ""}</div>
          <span class="autoops"><button class="btn outline sm" data-set="runAuto" data-id="${esc(a.entity_id)}">今すぐ実行</button>
          <span class="swl"><label class="sw"><input type="checkbox" data-set="autoOn" data-id="${esc(a.entity_id)}" ${a.state === "on" ? "checked" : ""} aria-label="${esc(nameOf(a))}を有効にする"><span></span></label><span class="swt">${a.state === "on" ? "オン" : "オフ"}</span></span></span></div>`;
      }).join("")}</div>`;
  }

  function entityOptions(domains, selected, withEl) {
    if (!S.ha) return "";
    const groups = {};
    Object.values(S.ha.states).filter((e) => domains.includes(dom(e))).forEach((e) => { (groups[dom(e)] = groups[dom(e)] || []).push(e); });
    // 部屋の名前も出す（「寝室・エアコン」）。部屋ごとに並べる
    const label = (id) => entName(id);
    let h = domains.filter((d) => groups[d]).map((d) => `<optgroup label="${esc(DOMAIN_LABEL2[d] || d)}">${groups[d]
      .sort((a, b) => label(a.entity_id).localeCompare(label(b.entity_id), "ja"))
      .map((e) => `<option value="${esc(e.entity_id)}" ${e.entity_id === selected ? "selected" : ""}>${esc(label(e.entity_id))}</option>`).join("")}</optgroup>`).join("");
    const els = withEl ? elDevs().filter((x) => domains.includes(x.id.split(".")[0]) && !(S.ha.states[x.id])) : [];
    if (els.length) h += `<optgroup label="ECHONET Lite（サーバーが直接操作）">${els.sort((a, b) => label(a.id).localeCompare(label(b.id), "ja"))
      .map((x) => `<option value="${esc(x.id)}" ${x.id === selected ? "selected" : ""}>${esc(label(x.id))}</option>`).join("")}</optgroup>`;
    return h;
  }

  /** エアコンの風量。keep なら「そのまま」（変えない）も出す */
  function fanChipsHTML(a, i, keep) {
    const ent = S.ha && S.ha.states[a.entity];
    const fans = (ent && ent.attributes.fan_modes) || (isElId(a.entity) ? ["auto", "1", "2", "3", "4", "5", "6", "7", "8"] : ["auto", "low", "medium", "high"]);
    return `<div class="fl" style="margin-top:4px">風量</div><div class="chips">${keep ? `<button class="chip${a.data.fan_mode === undefined ? " sel" : ""}" data-set="actFan" data-id="${i}" data-v="">そのまま</button>` : ""}${fans.map((f) => `<button class="chip${a.data.fan_mode === f ? " sel" : ""}" data-set="actFan" data-id="${i}" data-v="${esc(f)}">${esc(fanLabel(f))}</button>`).join("")}</div>`;
  }
  /** −／＋ のボタンと、数字の入力欄。set は data-set の名前、extra は data-* の追加 */
  function numInputHTML(set, value, unit, extra) {
    return `<span class="numin"><button class="tb" data-set="${set}" ${extra} data-d="-1">−</button><input type="number" inputmode="decimal" step="0.5" data-set="${set}" ${extra} data-d="in" value="${esc(String(value))}"><span class="u">${unit}</span><button class="tb" data-set="${set}" ${extra} data-d="1">＋</button></span>`;
  }
  /** −／＋ なら step だけ足し、入力欄なら書かれた値にする（範囲に入れる） */
  /** 入れた文字を数字として読む（全角の数字・小数点・マイナス、3桁のカンマ、「円」「L」「℃」などの単位が混ざっていても）。読めなければ null */
  function parseNum(t) {
    let x = String(t == null ? "" : t).replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
      .replace(/[．。]/g, ".").replace(/[－−ー‐―]/g, "-").replace(/，/g, ",").trim()
      .replace(/(\d),(?=\d{3}(\D|$))/g, "$1").replace(",", ".").replace(/\s|円|／?\/?kWh|kW|L|℃|%|％|時/g, "");
    if (x === "") return null;
    const v = Number(x);
    return isFinite(v) ? v : null;
  }
  function numFrom(el, cur, step, min, max) {
    let v = el.dataset.d === "in" ? parseNum(el.value) : Number(cur) + Number(el.dataset.d) * step;
    if (v === null) v = NaN;
    if (isNaN(v)) v = Number(cur);
    if (el.dataset.d !== "in") v = Math.round(v / step) * step;
    return Math.round(Math.max(min, Math.min(max, v)) * 10) / 10;
  }

  function condsHTML(r) {
    const chip = (sel, set, id, label, i) => `<button class="chip${sel ? " sel" : ""}" data-set="${set}" data-id="${esc(id)}" data-i="${i}">${esc(label)}</button>`;
    return (r.conds || []).map((c, i) => {
      let body = "";
      if (c.kind === "numeric") {
        const hum = numIsHum(c.entity);
        body = `<div class="chips">${thermos().map((th) =>
          (th.tId ? chip(c.entity === th.tId, "condEntity", th.tId, `🌡 ${th.name}${th.temp != null ? " " + (Math.round(th.temp * 10) / 10) + "℃" : ""}`, i) : "") +
          (th.hId ? chip(c.entity === th.hId, "condEntity", th.hId, `💧 ${th.name}${th.hum != null ? " " + Math.round(th.hum) + "%" : ""}`, i) : "")).join("")}</div>
          <div class="brow" style="align-items:center">${chip(!c.above, "condAbove", "0", "より低い", i)}${chip(c.above, "condAbove", "1", "より高い", i)}
          ${numInputHTML("condStep", c.value, hum ? "%" : "℃", `data-i="${i}"`)}</div>`;
      } else {
        body = `<div class="chips">${condStateOptions(c.entity.split(".")[0]).map(([v, l]) => chip(c.state === v, "condState", v, l, i)).join("")}</div>`;
      }
      return `<div class="act"><div class="brow" style="align-items:center"><b style="flex:1">もし ${esc(ruleCondLabel(c))}</b><button class="tbtn" data-set="condDel" data-i="${i}">✕</button></div>${body}</div>`;
    }).join("");
  }

  function editorHTML() {
    const r = S.edit;
    const t = r.trigger;
    const chip = (sel, set, id, label) => `<button class="chip${sel ? " sel" : ""}" data-set="${set}" data-id="${esc(id)}">${esc(label)}</button>`;
    let trig = "";
    if (!r.schedule) {
      trig += `<div class="chips">${chip(t.kind === "time", "trigKind", "time", "時刻")}${chip(t.kind === "state", "trigKind", "state", "機器の状態")}
        ${chip(t.kind === "sun" && t.event === "sunrise", "trigKind", "sunrise", "日の出")}${chip(t.kind === "sun" && t.event === "sunset", "trigKind", "sunset", "日の入り")}${chip(t.kind === "numeric", "trigKind", "numeric", "温度計（温度・湿度）")}</div>`;
    }
    if (t.kind === "time") trig += `<input type="time" data-set="trigAt" value="${esc(t.at)}">`;
    else if (t.kind === "state") {
      trig += `<select data-set="trigEntity"><option value="">機器を選ぶ…</option>${entityOptions(TRIGGER_DOMAINS, t.entity)}</select>`;
      if (t.entity) trig += `<div class="chips">${stateOptions(t.entity.split(".")[0]).map(([v, l]) => chip(t.to === v, "trigTo", v, l)).join("")}</div>`;
    } else if (t.kind === "numeric") {
      const hum = numIsHum(t.entity), u = hum ? "%" : "℃";
      const ths = thermos();
      trig += `<div class="note">どの温度計で決めるか</div><div class="chips">${ths.map((th) =>
        (th.tId ? chip(t.entity === th.tId, "numEntity", th.tId, `🌡 ${th.name}${th.temp != null ? " " + (Math.round(th.temp * 10) / 10) + "℃" : ""}`) : "") +
        (th.hId ? chip(t.entity === th.hId, "numEntity", th.hId, `💧 ${th.name}${th.hum != null ? " " + Math.round(th.hum) + "%" : ""}`) : "")).join("")}</div>
        ${ths.length ? "" : `<div class="warn">温度計が見つかりません（SwitchBot 温湿度計などを Home Assistant に取り込むと出てきます）</div>`}
        <div class="chips">${chip(t.above, "numAbove", "1", hum ? "上回ったとき（じめじめ）" : "上回ったとき（暑い）")}${chip(!t.above, "numAbove", "0", hum ? "下回ったとき（乾燥）" : "下回ったとき（寒い）")}</div>
        <div class="brow" style="align-items:center">${numInputHTML("numStep", t.value, u, "")}</div>
        <div class="note">続いたら動かす（すぐ上下するときの、むだな動きを防ぐ）</div>
        <div class="chips">${[[0, "すぐ"], [5, "5分"], [10, "10分"], [30, "30分"]].map(([m, l]) => chip((t.forMin || 0) === m, "numFor", String(m), l)).join("")}</div>
        <div class="note">※ 境目をまたいだときに1回動きます（${t.value}${u} より${t.above ? "上" : "下"}のあいだ、くり返しは動きません）。</div>`;
    } else {
      trig += `<div class="brow" style="align-items:center"><button class="tb" data-set="sunOff" data-id="-10">−10分</button>
        <span style="font-size:20px;min-width:90px;text-align:center">${t.offset < 0 ? -t.offset + "分前" : t.offset > 0 ? t.offset + "分後" : "ちょうど"}</span>
        <button class="tb" data-set="sunOff" data-id="10">＋10分</button></div>`;
    }
    const days = `<div class="chips">${WEEKDAYS.map((d) => chip(r.weekdays.includes(d), "wd", d, WD[d])).join("")}
      <button class="tbtn" data-set="wdPreset" data-id="weekday">平日</button><button class="tbtn" data-set="wdPreset" data-id="weekend">土日</button><button class="tbtn" data-set="wdPreset" data-id="all">毎日</button></div>`;
    const range = r.schedule ? "" : `<div class="field"><div class="fl">時間帯で絞る</div>
      <label class="row"><span class="rt">特定の時間帯だけ動かす<div class="sub">例：夜（18:00〜23:00）だけ</div></span><input type="checkbox" data-set="rangeOn" ${r.after || r.before ? "checked" : ""}></label>
      ${r.after || r.before ? `<div class="brow" style="align-items:center"><input type="time" data-set="after" value="${esc(r.after || "00:00")}"> 〜 <input type="time" data-set="before" value="${esc(r.before || "23:59")}"></div>` : ""}</div>`;
    const acts = r.actions.map((a, i) => {
      const d = a.entity.split(".")[0];
      const ops = opsFor(d), oi = opIndex(a), op = ops[oi];
      let param = "";
      if (op && op[2] === "brightness_pct") param = `<div class="rng"><div class="rl">明るさ ${a.data.brightness_pct}%</div><input type="range" min="1" max="100" value="${a.data.brightness_pct}" data-set="actParam" data-id="${i}"></div>`;
      if (op && op[2] === "position") param = `<div class="rng"><div class="rl">開き具合 ${a.data.position}%</div><input type="range" min="0" max="100" value="${a.data.position}" data-set="actParam" data-id="${i}"></div>`;
      if (op && op[2] === "temperature") param = `<div class="brow" style="align-items:center"><span class="sl">温度</span>${numInputHTML("actTemp", a.data.temperature, "℃", `data-id="${i}"`)}</div>`;
      if (op && op[2] === "fan_mode") param = fanChipsHTML(a, i, false);
      if (op && (op[2] === "hvac_mode" || op[2] === "mode_temp")) {
        const ent = S.ha && S.ha.states[a.entity];
        const modes = ((ent && ent.attributes.hvac_modes) || ["cool", "heat", "dry", "fan_only", "auto"]).filter((m) => m !== "off");
        param = `<div class="chips">${modes.map((m) => `<button class="chip${a.data.hvac_mode === m ? " sel" : ""}" data-set="actMode" data-id="${i}" data-v="${esc(m)}">${esc(hvacLabel(m))}</button>`).join("")}</div>`;
        param += fanChipsHTML(a, i, true);
        if (op[2] === "mode_temp" && !["fan_only", "dry"].includes(a.data.hvac_mode)) param += `<div class="brow" style="align-items:center"><span class="sl">設定温度</span>${numInputHTML("actTemp", a.data.temperature ?? 25, "℃", `data-id="${i}"`)}</div>`;
      }
      return `<div class="act"><div class="head"><div class="ht"><div class="dom">${esc(DOMAIN_LABEL2[d] || d)}${isElId(a.entity) ? "・ECHONET Lite（サーバーが操作）" : ""}</div><div class="name">${esc(entName(a.entity))}</div></div>
        <button class="tbtn danger" data-set="actDel" data-id="${i}">削除</button></div>
        <div class="chips">${ops.map((o, j) => `<button class="chip${j === oi ? " sel" : ""}" data-set="actOp" data-id="${i}" data-j="${j}">${esc(o[1])}</button>`).join("")}</div>${param}</div>`;
    }).join("");
    return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="editCancel">← キャンセル</button>
        <h2>${r.isNew ? "新しい" : ""}${r.schedule ? "スケジュール" : "オートメーション"}</h2></div>
      <div class="field"><div class="fl">名前</div><input type="text" data-set="ruleName" value="${esc(r.name)}" placeholder="空欄なら内容から自動で付けます"></div>
      <div class="field"><div class="fl">${r.schedule ? "時刻" : "きっかけ"}</div>${trig}</div>
      <div class="field"><div class="fl">曜日（選ばなければ毎日）</div>${days}</div>
      ${range}
      <div class="field"><div class="fl">条件（選ばなければ、いつも動かす）</div>
        <div class="note">${r.schedule ? "その時刻" : "きっかけ"}の時点で、ここに並べた条件をすべて満たすときだけ実行します。例：6時の時点で寝室が20℃より低ければ暖房をつける。</div>
        ${condsHTML(r)}
        <div class="brow"><button class="tbtn" data-set="condAddNum">＋ 温度計の条件</button>
          <select data-set="condAddState"><option value="">＋ 機器の状態の条件…</option>${entityOptions(TRIGGER_DOMAINS, "")}</select></div></div>
      <div class="field"><div class="fl">実行すること</div>${acts}
        <select data-set="actAdd"><option value="">＋ 操作を追加（機器を選ぶ）…</option>${entityOptions(ACTION_DOMAINS, "", true)}</select></div>
      <div class="summary" id="ruleSummary">内容：${esc(ruleSummary(r))}</div>
      ${S.editError ? `<div class="warn">${esc(S.editError)}</div>` : ""}
      <div class="brow rulefoot"><button class="btn" data-set="ruleSave" ${S.saving ? "disabled" : ""}>${S.saving ? "保存しています…" : "保存"}</button>
        ${r.isNew ? "" : `<button class="btn danger push" data-set="ruleDelete">この自動化を削除</button>`}</div></div>`;
  }

  // ---- 描き直しても、入力中の欄・カーソルの位置・スクロールを保つ（UI-004・UI-023） ----
  function focusKey(el) {
    if (!el || el === document.body) return null;
    if (el.id) return "#" + CSS.escape(el.id);
    const parts = [el.tagName.toLowerCase()];
    for (const a of ["data-set", "data-act", "data-id", "data-key", "data-f", "data-v", "data-i", "data-kind", "name"]) {
      const v = el.getAttribute(a);
      if (v !== null) parts.push(`[${a}="${CSS.escape(v)}"]`);
    }
    return parts.length > 1 ? parts.join("") : null;
  }
  function withKeptFocus(root, render) {
    const a = document.activeElement;
    const inside = a && root.contains(a);
    const key = inside ? focusKey(a) : null;
    let sel = null;
    try { if (inside && typeof a.selectionStart === "number") sel = [a.selectionStart, a.selectionEnd]; } catch (e) { sel = null; }
    const y = root.scrollTop;
    render();
    root.scrollTop = y;
    if (!key) return;
    const n = root.querySelector(key);
    if (!n) return;
    try {
      n.focus({ preventScroll: true });
      if (sel && typeof n.setSelectionRange === "function") n.setSelectionRange(sel[0], sel[1]);
    } catch (e) { /* フォーカスを戻せない要素は何もしない */ }
  }

  function renderAuto() {
    if (S.page !== "auto") return;
    const page = $("#page");
    withKeptFocus(page, () => { page.innerHTML = S.edit ? editorHTML() : autoListHTML(); });
  }

  async function onAutoSetting(el) {
    const r = S.edit;
    const id = el.dataset.id;
    switch (el.dataset.set) {
      case "autoTab": S.autoTab = Number(id); renderAuto(); return true;
      case "newRule": S.edit = newRule(S.autoTab === 0); S.editError = null; $("#page").scrollTop = 0; renderAuto(); return true;
      case "editRule": {
        if (el.tagName !== "DIV") return true;
        const a = S.ha && S.ha.states[id];
        if (!a) return true;
        const cid = a.attributes.id;
        if (!canEditRules()) S.autoMsg = "編集するには管理者ログインが必要です（⚙ 設定 → アカウント）";
        else if (!isAppRule(cid)) S.autoMsg = `「${nameOf(a)}」は Home Assistant で作られた自動化なので、Home Assistant の画面で編集してください`;
        else if (!S.rules[cid]) S.autoMsg = `「${nameOf(a)}」の設定を読み込めませんでした`;
        else { S.edit = JSON.parse(JSON.stringify(S.rules[cid])); S.editError = null; $("#page").scrollTop = 0; }
        renderAuto();
        return true;
      }
      case "runAuto": { const a = S.ha.states[id]; call(a, "trigger"); S.autoMsg = `「${nameOf(a)}」を実行しました`; renderAuto(); return true; }
      case "autoOn": call(S.ha.states[id], el.checked ? "turn_on" : "turn_off"); return true;
      case "editCancel": S.edit = null; renderAuto(); return true;
      case "trigKind":
        if (id === "time") r.trigger = { kind: "time", at: "07:00" };
        else if (id === "state") { if (r.trigger.kind !== "state") r.trigger = { kind: "state", entity: "", to: "on" }; }
        else if (id === "numeric") {
          if (r.trigger.kind !== "numeric") {
            const th = thermos().find((x) => x.tId);
            r.trigger = { kind: "numeric", entity: th ? th.tId : "", above: true, value: Math.round((th && th.temp != null ? th.temp : 27) + 1), forMin: 0 };
          }
        }
        else r.trigger = { kind: "sun", event: id, offset: 0 };
        break;
      case "trigAt": r.trigger.at = el.value || "07:00"; break;
      case "trigEntity": r.trigger = { kind: "state", entity: el.value, to: el.value ? stateOptions(el.value.split(".")[0])[0][0] : "on" }; break;
      case "trigTo": r.trigger.to = id; break;
      case "condAddNum": {
        const th = thermos().find((x) => x.tId);
        r.conds = (r.conds || []).concat([{ kind: "numeric", entity: th ? th.tId : "", above: false, value: Math.round(th && th.temp != null ? th.temp : 20) }]);
        break;
      }
      case "condAddState":
        if (el.value) r.conds = (r.conds || []).concat([{ kind: "state", entity: el.value, state: condStateOptions(el.value.split(".")[0])[0][0] }]);
        break;
      case "condDel": r.conds.splice(Number(el.dataset.i), 1); break;
      case "condEntity": {
        const c = r.conds[Number(el.dataset.i)], wasHum = numIsHum(c.entity), th = thermos().find((x) => x.tId === id || x.hId === id);
        c.entity = id;
        if (th && wasHum !== numIsHum(id)) c.value = numIsHum(id) ? Math.round((th.hum != null ? th.hum : 50) / 5) * 5 : Math.round(th.temp != null ? th.temp : 20);
        break;
      }
      case "condAbove": r.conds[Number(el.dataset.i)].above = id === "1"; break;
      case "condStep": { const c = r.conds[Number(el.dataset.i)], hum = numIsHum(c.entity); c.value = numFrom(el, c.value, hum ? 5 : 0.5, hum ? 0 : -10, hum ? 100 : 50); break; }
      case "condState": r.conds[Number(el.dataset.i)].state = id; break;
      case "numEntity": {
        const wasHum = numIsHum(r.trigger.entity), th = thermos().find((x) => x.tId === id || x.hId === id);
        r.trigger.entity = id;
        const nowHum = numIsHum(id);
        if (th && wasHum !== nowHum) r.trigger.value = nowHum ? Math.round((th.hum != null ? th.hum : 60) / 5) * 5 + 5 : Math.round((th.temp != null ? th.temp : 27) + 1);
        break;
      }
      case "numAbove": r.trigger.above = id === "1"; break;
      case "numStep": { const hum = numIsHum(r.trigger.entity); r.trigger.value = numFrom(el, r.trigger.value, hum ? 5 : 0.5, hum ? 0 : -10, hum ? 100 : 50); break; }
      case "numFor": r.trigger.forMin = Number(id); break;
      case "sunOff": r.trigger.offset += Number(id); break;
      case "wd": r.weekdays = r.weekdays.includes(id) ? r.weekdays.filter((d) => d !== id) : r.weekdays.concat([id]); break;
      case "wdPreset": r.weekdays = id === "weekday" ? ["mon", "tue", "wed", "thu", "fri"] : id === "weekend" ? ["sat", "sun"] : []; break;
      case "rangeOn": if (el.checked) { r.after = "18:00"; r.before = "23:00"; } else { r.after = null; r.before = null; } break;
      case "after": r.after = el.value || null; break;
      case "before": r.before = el.value || null; break;
      case "actAdd": if (el.value) r.actions.push(makeAct(el.value, opsFor(el.value.split(".")[0])[0])); break;
      case "actDel": r.actions.splice(Number(id), 1); break;
      case "actOp": { const a = r.actions[Number(id)]; r.actions[Number(id)] = makeAct(a.entity, opsFor(a.entity.split(".")[0])[Number(el.dataset.j)]); break; }
      case "actTemp": {
        const a = r.actions[Number(id)], ent = S.ha && S.ha.states[a.entity], at = (ent && ent.attributes) || {};
        a.data.temperature = numFrom(el, a.data.temperature ?? 25, Number(at.target_temp_step) || (isElId(a.entity) ? 1 : 0.5), Number(at.min_temp) || 16, Number(at.max_temp) || 32);
        break;
      }
      case "actMode": r.actions[Number(id)].data.hvac_mode = el.dataset.v; break;
      case "actFan": { const a = r.actions[Number(id)]; if (el.dataset.v) a.data.fan_mode = el.dataset.v; else delete a.data.fan_mode; break; }
      case "actParam": {
        const a = r.actions[Number(id)];
        const key = Object.keys(a.data)[0];
        a.data[key] = Number(el.value);
        break;
      }
      case "ruleSave": {
        if (!r.actions.length) { S.editError = "「＋ 操作を追加」で、実行することを1つ以上選んでください"; break; }
        if (r.trigger.kind === "state" && !r.trigger.entity) { S.editError = "きっかけにする機器を選んでください"; break; }
        if (r.trigger.kind === "numeric" && !r.trigger.entity) { S.editError = "きっかけにする温度計を選んでください"; break; }
        if ((r.conds || []).some((c) => c.kind === "numeric" && !c.entity)) { S.editError = "条件の温度計を選んでください"; break; }
        S.saving = true; S.editError = null; renderAuto();
        try {
          if (!r.name.trim()) r.name = ruleSummary(r).slice(0, 40);
          await rest("POST", "/api/config/automation/config/" + encodeURIComponent(r.id), toConfig(r));
          const saved = Object.assign({}, r); delete saved.isNew;
          S.rules[r.id] = saved;
          S.autoMsg = `「${r.name}」を保存しました`;
          S.edit = null;
        } catch (e) { S.editError = e.message; }
        S.saving = false;
        break;
      }
      case "ruleDelete":
        if (!confirm(`「${r.name || "この自動化"}」を Home Assistant から削除しますか？`)) return true;
        try {
          await rest("DELETE", "/api/config/automation/config/" + encodeURIComponent(r.id));
          delete S.rules[r.id];
          S.autoMsg = "削除しました";
          S.edit = null;
        } catch (e) { S.editError = e.message; }
        break;
      default: return false;
    }
    renderAuto();
    return true;
  }

  // ================= タイマー =================
  const timerRemaining = (t, now = Date.now()) => (t.endAt ? Math.max(0, t.endAt - now) : t.remainingMs);
  function formatRemaining(ms) {
    const s = Math.ceil(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
  }
  function speakRemaining(ms) {
    const s = Math.ceil(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    let r = "";
    if (h) r += h + "時間";
    if (m) r += m + "分";
    if (sec && !h) r += sec + "秒";
    return r || "0秒";
  }
  const KANJI = { "〇": 0, "零": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9 };
  function kanjiToNumber(str) {
    let total = 0, cur = 0;
    for (const c of str) {
      if (c in KANJI) cur = KANJI[c];
      else if (c === "十") { total += (cur || 1) * 10; cur = 0; }
      else if (c === "百") { total += (cur || 1) * 100; cur = 0; }
      else return null;
    }
    return total + cur;
  }
  function normalizeNumbers(text) {
    const half = text.replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));
    return half.replace(/[〇零一二三四五六七八九十百]+/g, (m) => { const n = kanjiToNumber(m); return n === null ? m : String(n); });
  }
  /** 声でのタイマー操作。該当しなければ null */
  function parseTimerCommand(raw) {
    const text = normalizeNumbers(raw).replace(/[\s　]/g, "");
    if (!text.includes("タイマー")) return null;
    if (/止め|とめ|キャンセル|消し|けし|解除|ストップ|終了|やめ/.test(text)) return { type: "cancel" };
    if (/残り|のこり|あと何|あとどれ|何分/.test(text)) return { type: "remaining" };
    const re = /(?:(\d+)時間)?(?:(\d+)分)?(?:(\d+)秒)?/g;
    let m;
    while ((m = re.exec(text)) !== null) {
      if (m[0] === "") { re.lastIndex++; continue; }
      let total = (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0);
      if (/\d+分半/.test(text)) total += 30;
      if (total <= 0) return null;
      const before = text.slice(0, m.index).replace(/の$/, "").trim();
      const label = before && before.length <= 12 && !before.includes("タイマー") ? before : "";
      return { type: "start", seconds: total, label };
    }
    return null;
  }

  function saveTimers() { store.set("timers", S.timers); }
  function addTimer(seconds, label) {
    unlockAlarm();
    const now = Date.now();
    S.timers.push({ id: now, label: (label || "").trim(), totalMs: seconds * 1000, endAt: now + seconds * 1000, remainingMs: seconds * 1000 });
    S.timers.sort((a, b) => timerRemaining(a) - timerRemaining(b));
    saveTimers();
    renderTimers();
  }
  function cancelTimer(id) { S.timers = S.timers.filter((t) => t.id !== id); saveTimers(); renderTimers(); }
  function pauseResumeTimer(id) {
    const now = Date.now();
    S.timers = S.timers.map((t) => t.id !== id ? t : t.endAt ? Object.assign({}, t, { endAt: null, remainingMs: timerRemaining(t, now) }) : Object.assign({}, t, { endAt: now + t.remainingMs }));
    saveTimers();
    renderTimers();
  }
  function checkTimers() {
    const now = Date.now();
    const done = S.timers.filter((t) => t.endAt && t.endAt <= now);
    if (done.length) {
      S.timers = S.timers.filter((t) => !done.includes(t));
      saveTimers();
      S.ringing = S.ringing.concat(done);
      addLog("タイマー終了");
      onUserInteraction();
      startAlarm();
      renderAlarm();
    }
    renderTimers(true);
  }

  // アラーム音（iOS は操作をきっかけに音を出せるようにしておく必要がある）
  let alarmCtx = null, alarmTimer = null;
  function unlockAlarm() {
    try {
      if (!alarmCtx) alarmCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (alarmCtx.state === "suspended") alarmCtx.resume();
    } catch (e) { /* noop */ }
  }
  function startAlarm() {
    if (alarmTimer || !alarmCtx) return;
    const until = Date.now() + 120000;
    const ring = () => {
      if (!S.ringing.length || Date.now() > until) { stopAlarm(); return; }
      const t0 = alarmCtx.currentTime;
      [0, 0.25, 0.5].forEach((dt) => {
        const o = alarmCtx.createOscillator(), g = alarmCtx.createGain();
        o.frequency.value = 1046;
        g.gain.setValueAtTime(0.3, t0 + dt);
        g.gain.exponentialRampToValueAtTime(0.001, t0 + dt + 0.2);
        o.connect(g); g.connect(alarmCtx.destination);
        o.start(t0 + dt); o.stop(t0 + dt + 0.2);
      });
    };
    ring();
    alarmTimer = setInterval(ring, 1200);
  }
  function stopAlarm() {
    clearInterval(alarmTimer);
    alarmTimer = null;
    S.ringing = [];
    renderAlarm();
  }

  function renderTimers(tickOnly) {
    const strip = $("#timerStrip");
    if (strip) {
      const now = Date.now();
      strip.innerHTML = S.timers.map((t) => `<button class="chip timer" data-set="openTimers">⏱ ${esc(t.label ? t.label + " " : "")}${formatRemaining(timerRemaining(t, now))}${t.endAt ? "" : "（停止中）"}</button>`).join("");
      strip.classList.toggle("hidden", !S.timers.length);
    }
    const list = $("#timerList");
    if (list) {
      const now = Date.now();
      const sig = S.timers.map((t) => t.id).join(",");
      if (list.dataset.sig !== sig || !S.timers.length) {
        list.dataset.sig = sig;
        list.innerHTML = S.timers.length ? S.timers.map((t) => `<div class="row timerrow" style="cursor:default" data-tid="${t.id}">
          <span class="rt"><span class="tremain" style="font-size:24px;font-variant-numeric:tabular-nums" aria-live="off"></span>
          <div class="sub"><b>${esc(t.label || "タイマー")}</b>（${speakRemaining(t.totalMs)}）<span class="tstate"></span></div></span>
          <button class="tbtn tpause" data-set="timerPause" data-id="${t.id}"></button>
          <button class="tbtn danger" data-set="timerCancel" data-id="${t.id}" aria-label="${esc(t.label || "タイマー")}を取り消す">取消</button></div>`).join("")
          : `<div class="note">動いているタイマーはありません</div>`;
      }
      for (const t of S.timers) {
        const row = list.querySelector(`[data-tid="${CSS.escape(String(t.id))}"]`);
        if (!row) continue;
        row.querySelector(".tremain").textContent = formatRemaining(timerRemaining(t, now));
        row.querySelector(".tstate").textContent = t.endAt ? "" : "・一時停止中";
        const b = row.querySelector(".tpause");
        const lbl = t.endAt ? "一時停止" : "再開";
        if (b.textContent !== lbl) b.textContent = lbl;
      }
    }
    const ss = $("#ssTimers");
    if (ss) ss.innerHTML = S.timers.map((t) => `<div>⏱ ${esc(t.label ? t.label + " " : "")}${formatRemaining(timerRemaining(t))}</div>`).join("");
    if (tickOnly) return;
  }

  function timersHTML() {
    return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="close">← 戻る</button><h2>⏱ タイマー</h2></div>
      <div class="field"><div class="fl">すぐ始める</div><div class="brow">${[1, 3, 5, 10, 15, 30, 60].map((m) => `<button class="tb" data-set="timerQuick" data-id="${m * 60}">${m >= 60 ? m / 60 + "時間" : m + "分"}</button>`).join("")}</div></div>
      <div class="field"><div class="fl">時間を指定</div>
        <div class="brow" style="align-items:center"><label class="numlab"><input type="number" id="tMin" min="0" max="999" value="3" style="width:90px" class="search" aria-label="分"> 分</label>
          <label class="numlab"><input type="number" id="tSec" min="0" max="59" value="0" style="width:90px" class="search" aria-label="秒"> 秒</label></div>
        <label class="fl" for="tLabel">名前（なくてもOK）</label>
        <input type="text" id="tLabel" class="search" placeholder="例：パスタ" style="max-width:420px">
        <div><button class="btn" data-set="timerStart">スタート</button></div></div>
      <div class="field"><div class="fl">動いているタイマー</div><div id="timerList"></div></div>
      ${SERVER_MODE ? `<div class="field"><div class="fl">機器のタイマー</div><div id="devTimerList">${devTimerListHTML()}</div></div>` : ""}
      <div class="note">音声でも「5分のタイマー」「パスタの3分タイマー」「タイマーの残りは？」「タイマーを止めて」と言えます。</div></div>`;
  }

  function renderAlarm() {
    const box = $("#alarm");
    if (!box) return;
    box.classList.toggle("hidden", !S.ringing.length);
    box.innerHTML = S.ringing.length ? `<div class="alarm-in"><div style="font-size:80px">⏰</div><div style="font-size:32px;font-weight:600">タイマーが終わりました</div>
      ${S.ringing.map((t) => `<div style="font-size:20px">${esc(t.label || "タイマー")}（${speakRemaining(t.totalMs)}）</div>`).join("")}
      <button class="btn" data-set="alarmStop" style="font-size:22px;padding:14px 40px">止める</button></div>` : "";
  }

  // ================= スクリーンセーバー =================
  let lastInteraction = Date.now();
  let ssTimer = null, ssIndex = 0;
  function onUserInteraction() {
    lastInteraction = Date.now();
    if (S.ssOn) hideScreensaver();
  }
  function showScreensaver() {
    if (S.ssOn) return;
    S.ssOn = true;
    const box = $("#saver");
    box.classList.remove("hidden");
    renderSaverInfo();
    nextPhoto();
    clearInterval(ssTimer);
    ssTimer = setInterval(nextPhoto, (S.ss.interval || 20) * 1000);
  }
  function hideScreensaver() {
    S.ssOn = false;
    clearInterval(ssTimer);
    $("#saver").classList.add("hidden");
  }
  function nextPhoto() {
    const imgs = document.querySelectorAll("#saver .ssimg");
    if (!S.photos.length || imgs.length < 2) return;
    ssIndex = (ssIndex + 1) % S.photos.length;
    const w = Math.min(1920, Math.round(window.innerWidth * (window.devicePixelRatio || 1)));
    const h = Math.min(1920, Math.round(window.innerHeight * (window.devicePixelRatio || 1)));
    const [a, b] = imgs[0].classList.contains("show") ? [imgs[1], imgs[0]] : [imgs[0], imgs[1]];
    a.onload = () => { a.classList.add("show"); b.classList.remove("show"); };
    a.src = `${S.photos[ssIndex]}=w${w}-h${h}`;
  }
  function renderSaverInfo() {
    const now = new Date();
    const t = $("#ssTime"), d = $("#ssDate"), w = $("#ssWeather");
    if (!t) return;
    t.textContent = `${now.getHours()}:${pad(now.getMinutes())}`;
    d.textContent = `${now.getMonth() + 1}月${now.getDate()}日（${DOW[now.getDay()]}）`;
    const we = weatherEntity();
    if (we && !isUnav(we)) w.textContent = `${condEmoji(we.state)} ${num(we.attributes, "temperature") !== null ? fmt(num(we.attributes, "temperature")) + "°" : ""} ${condLabel(we.state)}`;
  }
  function checkIdle() {
    const idle = Date.now() - lastInteraction;
    if (S.cam) lastInteraction = Date.now(); // カメラを見ている間は出さない
    if (S.ss.enabled && !S.ssOn && !S.page && !S.detail && !S.info && !S.thermo && !S.cam && !S.ringing.length && !S.busy && S.voice.type !== "listen" && idle > (S.ss.minutes || 5) * 60000) showScreensaver();
    if (S.ssOn) renderSaverInfo();
  }

  /** Google フォトの共有アルバムの写真一覧（専用サーバー経由。ブラウザからは直接読めないため） */
  async function loadPhotos() {
    const url = S.ss.album;
    if (!url) { S.photos = []; S.photoStatus = null; return; }
    if (!SERVER_MODE) { S.photoStatus = "写真の表示は、Proxmox の専用サーバーから開いたときに使えます（今は時計のみ表示）"; return; }
    S.photoStatus = "アルバムを読み込んでいます…";
    renderPhotoStatus();
    try {
      const r = await fetch("/photos-api/album?url=" + encodeURIComponent(url), { cache: "no-store" });
      if (r.status === 404) throw new Error("サーバーに写真の機能がありません。Proxmox でセットアップ用コマンドをもう一度実行してください（README 5-1）");
      const j = await r.json();
      if (j.error) throw new Error(j.error);
      S.photos = (j.photos || []).sort(() => Math.random() - 0.5);
      S.photoStatus = S.photos.length ? `${S.photos.length}枚の写真を読み込みました` : "写真が見つかりませんでした。共有リンク（photos.app.goo.gl/…）か確認してください";
    } catch (e) {
      S.photoStatus = "アルバムを読み込めませんでした：" + e.message;
    }
    renderPhotoStatus();
  }
  function renderPhotoStatus() { const el = $("#photoStatus"); if (el) el.textContent = S.photoStatus || ""; }

  function screensaverSettingsHTML() {
    const c = (sel, id, label) => `<button class="chip${sel ? " sel" : ""}" data-set="ssMin" data-id="${id}">${label}</button>`;
    const ci = (sel, id, label) => `<button class="chip${sel ? " sel" : ""}" data-set="ssInt" data-id="${id}">${label}</button>`;
    return `<label class="row"><span class="rt">操作していないときに写真を表示する<div class="sub">時計・天気・タイマーも一緒に表示します。画面に触れると元に戻ります。写真を設定しない場合は時計だけを表示します。</div></span>
        <input type="checkbox" data-set="ssOn" ${S.ss.enabled ? "checked" : ""}></label>
      <div class="note">表示するまでの時間</div><div class="chips">${[1, 3, 5, 10, 15, 30].map((m) => c(S.ss.minutes === m, m, m + "分")).join("")}</div>
      <div class="note" style="margin-top:8px">写真を切り替える間隔</div><div class="chips">${[10, 20, 30, 60, 300].map((x) => ci(S.ss.interval === x, x, x >= 60 ? x / 60 + "分" : x + "秒")).join("")}</div>
      <div class="note" style="margin-top:8px">Google フォトのアルバムを「共有」→「リンクを作成」し、そのリンク（https://photos.app.goo.gl/…）を貼り付けてください。Google の仕様変更で、共有リンクにしたアルバムだけ表示できます（非公式の方法のため、Google 側の変更で使えなくなることがあります）。</div>
      <input type="text" class="search" id="ssAlbum" value="${esc(S.ss.album || "")}" placeholder="Google フォトの共有アルバムのリンク">
      <div class="brow"><button class="btn" data-set="ssAlbumSave">保存して読み込む</button><button class="btn outline" data-set="ssTest">今すぐ表示して試す</button></div>
      <div class="note" id="photoStatus">${esc(S.photoStatus || "")}</div>`;
  }

  function voiceDiagHTML() {
    return `<div class="note" style="margin-top:10px">呼びかけに反応しないときは、マイクのテストと動作ログで原因を確認できます。</div>
      <div class="brow"><button class="btn outline" data-set="micTest">🎤 マイクのテスト（5秒）</button><button class="tbtn" data-set="logToggle">音声の動作ログ</button></div>
      <div class="bar" style="margin:6px 0"><div id="micBar" style="height:100%;width:0;background:var(--primary)"></div></div>
      <div class="note" id="micTestResult"></div>
      <pre id="voiceLog" class="hidden" style="white-space:pre-wrap;font-size:12px;background:var(--surface2);padding:10px;border-radius:10px;max-height:300px;overflow:auto">${esc(voiceLog.join("\n") || "まだ記録はありません。マイクボタンや呼びかけを試すと、ここに流れが表示されます。")}</pre>`;
  }

  async function onExtraSetting(el) {
    const id = el.dataset.id;
    switch (el.dataset.set) {
      case "openTimers": openPage("timers"); return true;
      case "timerQuick": addTimer(Number(id), ""); return true;
      case "timerStart": {
        const sec = (Number($("#tMin").value) || 0) * 60 + (Number($("#tSec").value) || 0);
        if (sec > 0) { addTimer(sec, $("#tLabel").value); $("#tLabel").value = ""; }
        return true;
      }
      case "timerPause": pauseResumeTimer(Number(id)); return true;
      case "timerCancel": cancelTimer(Number(id)); return true;
      case "alarmStop": stopAlarm(); return true;
      case "ssOn": S.ss.enabled = el.checked; store.set("ss", S.ss); if (el.checked) unlockAlarm(); return true;
      case "ssMin": S.ss.minutes = Number(id); store.set("ss", S.ss); openPageKeep(); return true;
      case "ssInt": S.ss.interval = Number(id); store.set("ss", S.ss); openPageKeep(); return true;
      case "ssAlbumSave": S.ss.album = $("#ssAlbum").value.trim(); store.set("ss", S.ss); loadPhotos(); return true;
      case "ssTest": closePage(); showScreensaver(); return true;
      case "micTest": micTest(); return true;
      case "logToggle": $("#voiceLog").classList.toggle("hidden"); return true;
      default: return false;
    }
  }

  // ================= 機器のアイコン =================
  // Android 版と同じく、すべて同じ大きさの丸いバッジ。オンのときは色付き
  const ICON_PATHS = {
    light: '<circle cx="12" cy="10" r="5.5" fill="currentColor"/><rect x="9.5" y="15.5" width="5" height="3" fill="currentColor"/><rect x="10" y="19.5" width="4" height="2" rx="1" fill="currentColor"/>',
    switch: '<rect x="3" y="3" width="18" height="18" rx="5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="8" y="8" width="2" height="5" rx="1" fill="currentColor"/><rect x="14" y="8" width="2" height="5" rx="1" fill="currentColor"/><circle cx="12" cy="16.5" r="1.3" fill="currentColor"/>',
    fan: '<g fill="currentColor"><ellipse cx="12" cy="6" rx="2.6" ry="5"/><ellipse cx="12" cy="6" rx="2.6" ry="5" transform="rotate(120 12 12)"/><ellipse cx="12" cy="6" rx="2.6" ry="5" transform="rotate(240 12 12)"/><circle cx="12" cy="12" r="2.4"/></g>',
    cover: '<rect x="3" y="3" width="18" height="2.5" rx="1" fill="currentColor"/><path d="M5 6v14M9 6v14M15 6v14M19 6v14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    climate: '<rect x="2.5" y="5" width="19" height="9" rx="3" fill="none" stroke="currentColor" stroke-width="2"/><path d="M6 11h12M8 17l-1 3M12 17v3M16 17l1 3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    input_boolean: '<rect x="2.5" y="7" width="19" height="10" rx="5" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="16" cy="12" r="3" fill="currentColor"/>',
    scene: '<path d="M12 3l2.6 5.6 6 .7-4.5 4.1 1.2 6L12 16.4 6.7 19.4l1.2-6L3.4 9.3l6-.7z" fill="currentColor"/>',
    script: '<path d="M8 5l11 7-11 7z" fill="currentColor"/>',
    camera: '<rect x="2" y="7" width="20" height="13" rx="3" fill="none" stroke="currentColor" stroke-width="2"/><rect x="8" y="3.5" width="8" height="4" rx="1.2" fill="currentColor"/><circle cx="12" cy="13.5" r="3.6" fill="none" stroke="currentColor" stroke-width="2"/>',
  };
  function iconHTML(e) {
    const d = dom(e);
    const active = isOn(e) && d !== "scene" && d !== "script" && !isUnav(e);
    return `<span class="dicon${active ? " on" : ""}"><svg viewBox="0 0 24 24" width="22" height="22">${ICON_PATHS[d] || '<circle cx="12" cy="12" r="5" fill="currentColor"/>'}</svg></span>`;
  }

  // ================= 設定のメニュー =================
  // [キー, アイコン, 名前, 説明, 詳しい設定か]
  const SETTINGS_MENU = [
    ["display", "🎨", "ホーム画面の表示", "かんたん表示、家族ごとの表示・並び順、配色、天気"],
    ["rooms", "🚪", "部屋と機器", "部屋の並び順・追加、表示する機器"],
    ["thermo", "🌡", "温度計", "表示する温度計、名前"],
    ["voice", "🎤", "音声と自宅の位置", "呼びかけ、アシスタント、マイクのテスト、自宅の位置"],
    ["selfsuff", "🌱", "電気の自給率を上げる", "余った電気のお知らせ・エアコン・充電など（使うものを選ぶ）"],
    ["runalert", "⏰", "つけっぱなしのお知らせ", "エアコンなどが決めた時間より長く動いていたら、スマートフォンに知らせる"],
    ["calendar", "📅", "カレンダー（TimeTree）", "予定を表示するカレンダー"],
    ["climate", "❄", "エアコンの自動温度調整", "サーバーでの調整の様子、全体のオン/オフ"],
    ["system", "⚙", "アプリの更新・設定の保存", "更新の確認、設定の書き出し・読み込み"],
    ["screensaver", "🖼", "スクリーンセーバー", "Google フォトのアルバム、表示までの時間", true],
    ["cameras", "📷", "カメラ", "画質の選び方", true],
    ["camrec", "🎞", "カメラの記録と動き", "2日分の記録、動きのある時間・寝た時刻の目安", true],
    ["oplog", "📜", "操作の記録", "だれが・いつ・何を操作したか", true],
    ["connection", "🌐", "接続", "このアプリのアドレス", true],
    ["account", "👤", "アカウント", "使っているアカウントの一覧、管理者ログイン、ログアウト", true],
  ].filter((m) => !["selfsuff", "runalert"].includes(m[0]) || SERVER_MODE);

  // ================= 表示のユーザー（家族ごとのホーム画面） =================
  const SECTIONS = [
    ["clock", "時計・天気（マイクボタン）", true],
    ["thermo", "温度・湿度（SwitchBot などの温度計）", true],
    ["calendar", "予定（TimeTree）", true],
    ["timers", "タイマーの残り時間", true],
    ["energy", "今日の電気（エネルギーの収支）", true],
    ["automations", "オートメーション・シーンのすぐ実行ボタン", false],
    ["devices", "機器", true],
  ];
  const defaultSections = () => SECTIONS.map(([key, , visible]) => ({ key, visible }));
  function completeSections(list) {
    const out = (list || []).filter((s) => SECTIONS.some(([k]) => k === s.key));
    SECTIONS.forEach(([key, , visible], i) => {
      if (out.some((s) => s.key === key)) return;
      const prev = SECTIONS.slice(0, i).map(([k]) => k).reverse().find((k) => out.some((s) => s.key === k));
      const at = prev ? out.findIndex((s) => s.key === prev) + 1 : 0;
      out.splice(at, 0, { key, visible });
    });
    return out;
  }
  function profiles() {
    let list = store.get("profiles");
    if (!Array.isArray(list) || !list.length) list = [{ id: "p_default", name: "みんな", sections: defaultSections(), theme: S.theme }];
    return list.map((p) => Object.assign({}, p, { sections: completeSections(p.sections) }));
  }
  function activeProfile() {
    const list = profiles();
    return list.find((p) => p.id === store.get("activeProfile")) || list[0];
  }
  function saveProfiles(list) { store.set("profiles", list); }
  function updateProfile(id, fn) {
    const list = profiles().map((p) => (p.id === id ? fn(Object.assign({}, p, { sections: p.sections.map((s) => Object.assign({}, s)) })) : p));
    saveProfiles(list);
    layoutHome();
  }
  function switchProfile(id) {
    store.set("activeProfile", id);
    const p = activeProfile();
    if (p.theme) { S.theme = p.theme; store.set("theme", S.theme); applyTheme(); }
    layoutHome();
    renderProfileBar();
  }
  function renderProfileBar() {
    const bar = $("#profileBar");
    if (!bar) return;
    const list = profiles();
    if (list.length <= 1) { bar.innerHTML = ""; return; }
    const cur = activeProfile();
    bar.innerHTML = list.map((p) => `<button class="chip sm${p.id === cur.id ? " sel" : ""}" data-set="profile" data-id="${esc(p.id)}">👤 ${esc(p.name)}</button>`).join("");
  }
  /** ホーム画面の項目を、選んでいるユーザーの並び順・表示に合わせる */
  function layoutHome() {
    const home = $("#home");
    if (!home) return;
    const p = activeProfile();
    p.sections.forEach((s) => {
      const el = home.querySelector(`[data-sec="${s.key}"]`);
      if (!el) return;
      home.appendChild(el);
      el.classList.toggle("off", !s.visible);
    });
    renderQuick();
  }
  function profilesHTML() {
    const list = profiles(), cur = activeProfile();
    return `<div class="note">家族それぞれで、ホーム画面に出す項目と並び順を変えられます。上部の名前のボタンで切り替えます（この端末の中だけの設定です）。</div>
      <div class="chips" style="margin:8px 0">${list.map((p) => `<button class="chip${p.id === cur.id ? " sel" : ""}" data-set="profile" data-id="${esc(p.id)}">👤 ${esc(p.name)}</button>`).join("")}
        <button class="tbtn" data-set="profileAdd">＋ 追加</button></div>
      <div class="card" style="margin-bottom:8px"><div class="brow profhead" style="align-items:center"><b style="flex:1">✎ 編集中：「${esc(cur.name)}」のホーム画面</b>
        <button class="tbtn" data-set="profileRename">名前を変える</button>${list.length > 1 ? `<button class="tbtn danger" data-set="profileDel">削除</button>` : ""}</div>
        ${cur.sections.map((s, i) => {
          const label = (SECTIONS.find(([k]) => k === s.key) || [])[1] || s.key;
          return `<div class="row editrow" style="cursor:default"><span class="ord"><button class="tbtn" data-set="secUp" data-id="${i}" ${i === 0 ? "disabled" : ""} aria-label="${esc(label)}を上へ">▲</button>
            <button class="tbtn" data-set="secDown" data-id="${i}" ${i === cur.sections.length - 1 ? "disabled" : ""} aria-label="${esc(label)}を下へ">▼</button></span>
            <span class="rt">${esc(label)}</span><label class="vis"><input type="checkbox" data-set="secVis" data-id="${i}" ${s.visible ? "checked" : ""}> 表示する</label></div>`;
        }).join("")}
        <div class="note">配色を選ぶと、このユーザーの配色として覚えます。</div></div>`;
  }

  // ================= すぐ実行ボタン（シーン・スクリプト・オートメーション） =================
  function renderQuick() {
    const box = $("#quick");
    if (!box || !S.ha) return;
    const list = Object.values(S.ha.states).filter((e) => ["scene", "script", "automation"].includes(dom(e)))
      .sort((a, b) => ["scene", "script", "automation"].indexOf(dom(a)) - ["scene", "script", "automation"].indexOf(dom(b)) || nameOf(a).localeCompare(nameOf(b), "ja"));
    box.innerHTML = list.length ? `<div class="chips">${list.slice(0, 30).map((e) => `<button class="chip" data-set="quickRun" data-id="${esc(e.entity_id)}">${dom(e) === "automation" ? "⚡" : dom(e) === "scene" ? "★" : "▶"} ${esc(nameOf(e))}</button>`).join("")}</div>` : "";
  }

  // ================= 機器の動作の詳細（ⓘ） =================
  async function restGet(path) {
    const token = await accessToken();
    const r = await fetch(path, { headers: { Authorization: "Bearer " + token } });
    if (!r.ok) throw new Error("読み込めませんでした (HTTP " + r.status + ")");
    return r.json();
  }
  async function fetchHistory(ids, hours) {
    const now = new Date(), start = new Date(now.getTime() - hours * 3600000);
    const q = `/api/history/period/${encodeURIComponent(start.toISOString())}?filter_entity_id=${ids.join(",")}&end_time=${encodeURIComponent(now.toISOString())}&minimal_response&no_attributes`;
    const arr = await restGet(q);
    const out = {};
    (arr || []).forEach((list, k) => {
      if (!Array.isArray(list) || !list.length) return;
      const id = list[0].entity_id || ids[k];
      out[id] = list.map((j) => ({ t: Date.parse(j.last_changed || j.last_updated), s: j.state })).filter((p) => !isNaN(p.t));
    });
    return out;
  }
  const ago = (ms) => {
    const m = Math.floor((Date.now() - ms) / 60000);
    return m < 1 ? "たった今" : m < 60 ? m + "分前" : m < 1440 ? Math.floor(m / 60) + "時間前" : Math.floor(m / 1440) + "日前";
  };
  const hm = (ms) => jstTime(ms);
  async function showInfo(id) {
    const e = S.ha && S.ha.states[id];
    if (!e) return;
    S.info = { id, history: null, status: "履歴を読み込んでいます…" };
    renderInfo();
    try {
      const h = await fetchHistory([id], 24);
      const list = (h[id] || []).reduce((acc, p) => { if (!acc.length || acc[acc.length - 1].s !== p.s) acc.push(p); return acc; }, []).reverse();
      if (S.info && S.info.id === id) { S.info.history = list; S.info.status = list.length ? null : "直近24時間の記録はありません"; }
    } catch (err) { if (S.info && S.info.id === id) S.info.status = err.message; }
    renderInfo();
  }
  const SKIP_ATTRS = ["friendly_name", "supported_features", "supported_color_modes", "icon", "entity_picture", "access_token", "hvac_modes", "fan_modes", "swing_modes", "preset_modes", "min_color_temp_kelvin", "max_color_temp_kelvin", "min_mireds", "max_mireds"];
  function renderInfo() {
    const box = $("#info");
    if (!box) return;
    const i = S.info, e = i && S.ha && S.ha.states[i.id];
    box.classList.toggle("hidden", !e);
    if (!e) { box.innerHTML = ""; return; }
    const a = e.attributes || {};
    const attrs = Object.keys(a).filter((k) => !SKIP_ATTRS.includes(k)).slice(0, 30)
      .map((k) => `<div class="kv"><span>${esc(k)}</span><span>${esc(typeof a[k] === "object" ? JSON.stringify(a[k]) : isoLocal(a[k]) || a[k])}</span></div>`).join("");
    box.innerHTML = `<div class="dlg-bg" data-set="infoClose"></div><div class="dlg"><div class="pagebar"><h3 style="flex:1;margin:0">${iconHTML(e)} ${esc(nameOf(e))}</h3><button class="tbtn" data-set="infoClose">閉じる</button></div>
      <div class="kv"><span>いまの状態</span><b>${esc(stateLabel(e))}</b></div>
      <div class="kv"><span>最後に変わった</span><span>${e.last_changed ? hm(Date.parse(e.last_changed)) + "（" + ago(Date.parse(e.last_changed)) + "）" : "－"}</span></div>
      <div class="kv"><span>最後に更新</span><span>${e.last_updated ? hm(Date.parse(e.last_updated)) : "－"}</span></div>
      <div class="kv"><span>ID</span><span>${esc(e.entity_id)}</span></div>
      ${["switch", "input_boolean", "media_player"].includes(dom(e)) ? devTimerSectionHTML(e) : ""}
      <div class="sec">直近24時間の動き</div>
      ${i.status ? `<div class="note">${esc(i.status)}</div>` : (i.history || []).slice(0, 60).map((p) => `<div class="kv"><span>${hm(p.t)}</span><span>${esc(stateLabel(Object.assign({}, e, { state: p.s })))}</span></div>`).join("")}
      ${attrs ? `<div class="sec">詳しい情報</div>${attrs}` : ""}</div>`;
  }

  // ================= 温度計（SwitchBot など） =================
  const devClass = (e) => (e.attributes || {}).device_class || "";
  const unitOf = (e) => (e.attributes || {}).unit_of_measurement || "";
  const isTemp = (e) => dom(e) === "sensor" && (devClass(e) === "temperature" || ["°C", "℃", "°F"].includes(unitOf(e)));
  const isHum = (e) => dom(e) === "sensor" && (devClass(e) === "humidity" || (unitOf(e) === "%" && /湿度|humidity/i.test(nameOf(e))));
  const isBat = (e) => dom(e) === "sensor" && devClass(e) === "battery";
  const baseName = (n) => n.replace(/[\s_]*(温度|湿度|気温|電池|バッテリー|Temperature|Humidity|Battery)$/i, "").trim() || n;
  function thermos() {
    if (!S.ha) return [];
    const names = store.get("thermoNames", {});
    const groups = {};
    Object.values(S.ha.states).filter((e) => isTemp(e) || isHum(e) || isBat(e)).forEach((e) => {
      const dev = S.entityDevice[e.entity_id];
      const k = dev ? "dev:" + dev : "name:" + baseName(nameOf(e));
      (groups[k] = groups[k] || []).push(e);
    });
    const out = [];
    for (const [key, list] of Object.entries(groups)) {
      // 同じ機器にセンサーが複数あるときは、つながっていて、いちばん新しく更新されたものを使う
      const best = (f) => list.filter(f).sort((a, b) => ((isUnav(a) || isNaN(Number(a.state))) - (isUnav(b) || isNaN(Number(b.state)))) || String(b.last_updated || "").localeCompare(String(a.last_updated || "")))[0];
      const t = best(isTemp), h = best(isHum), b = list.find(isBat);
      if (!t && !h) continue;
      let temp = t ? Number(t.state) : NaN;
      if (t && unitOf(t) === "°F" && !isNaN(temp)) temp = (temp - 32) * 5 / 9;
      const dn = key.startsWith("dev:") ? S.deviceNames[key.slice(4)] : null;
      const original = dn || baseName(nameOf(t || h));
      out.push({ key, original, name: names[key] || original, temp: isNaN(temp) ? null : temp, hum: h && !isNaN(Number(h.state)) ? Number(h.state) : null,
        bat: b && !isNaN(Number(b.state)) ? Number(b.state) : null, tId: t ? t.entity_id : null, hId: h ? h.entity_id : null,
        unav: (!t || isUnav(t)) && (!h || isUnav(h)) });
    }
    return out.sort((a, b) => a.name.localeCompare(b.name, "ja"));
  }
  const LEVEL = ["#64b5f6", "#4dd0e1", "#81c784", "#ffb74d", "#e57373"];
  const tempLevel = (t) => (t === null ? 2 : t < 16 ? 0 : t < 20 ? 1 : t < 26 ? 2 : t < 29 ? 3 : 4);
  function renderThermos() {
    const box = $("#thermo");
    if (!box) return;
    const hidden = store.get("thermoHidden", []);
    const list = thermos().filter((t) => !hidden.includes(t.key));
    box.innerHTML = list.map((t) => `<button class="thermo" data-set="thermoOpen" data-id="${esc(t.key)}"><span class="lv" style="background:${LEVEL[tempLevel(t.temp)]}"></span>
      <span><span class="tn">${esc(t.name)}</span><span class="tvv">${t.unav ? "－" : t.temp !== null ? fmt(Math.round(t.temp * 10) / 10) + "℃" : "－"}${t.hum !== null ? `<small>💧${fmt(Math.round(t.hum))}%</small>` : ""}</span>
      ${t.bat !== null && t.bat < 20 ? `<span class="warn" style="font-size:11px">🔋 電池残り ${Math.round(t.bat)}%</span>` : ""}</span></button>`).join("");
  }
  async function openThermo(key, hours) {
    S.thermo = { key, hours: hours || (S.thermo && S.thermo.key === key ? S.thermo.hours : 24), temp: null, hum: null, status: "グラフを読み込んでいます…" };
    renderThermoDialog();
    const t = thermos().find((x) => x.key === key);
    if (!t) return;
    try {
      const ids = [t.tId, t.hId].filter(Boolean);
      const h = await fetchHistory(ids, S.thermo.hours);
      if (!S.thermo || S.thermo.key !== key) return;
      const pts = (id) => (id && h[id] ? h[id].map((p) => ({ t: p.t, v: Number(p.s) })).filter((p) => !isNaN(p.v)) : null);
      S.thermo.temp = pts(t.tId);
      S.thermo.hum = pts(t.hId);
      S.thermo.status = (S.thermo.temp || []).length + (S.thermo.hum || []).length ? null : "この期間の記録がありません";
    } catch (e) { if (S.thermo) S.thermo.status = e.message; }
    renderThermoDialog();
  }
  function chartSVG(pts, hours, color, unit) {
    const W = 640, H = 210, L = 62, B = 26, T = 18;
    const end = Date.now(), start = end - hours * 3600000;
    let lo = Math.min(...pts.map((p) => p.v)), hi = Math.max(...pts.map((p) => p.v));
    if (hi - lo < 2) { const c = (hi + lo) / 2; lo = c - 1; hi = c + 1; }
    lo = Math.floor(lo); hi = Math.ceil(hi);
    const x = (t) => L + (W - L) * Math.max(0, (t - start) / (end - start));
    const y = (v) => T + (H - B - T) * (1 - (v - lo) / (hi - lo));
    let g = "";
    for (let i = 0; i <= 4; i++) {
      const v = lo + (hi - lo) * i / 4, yy = y(v);
      g += `<line x1="${L}" x2="${W}" y1="${yy}" y2="${yy}" class="grid"/><text x="2" y="${yy + 4}" class="lbl">${Math.round(v)}${unit}</text>`;
    }
    const step = hours <= 24 ? 6 : 24;
    const tk = new Date(start); tk.setMinutes(0, 0, 0);
    if (step === 24) { tk.setHours(0); tk.setDate(tk.getDate() + 1); } else tk.setHours(tk.getHours() + (step - tk.getHours() % step || step));
    for (; tk.getTime() < end; tk.setHours(tk.getHours() + step)) {
      const xx = x(tk.getTime());
      g += `<line x1="${xx}" x2="${xx}" y1="${T}" y2="${H - B}" class="grid"/><text x="${xx}" y="${H - 6}" class="lbl" text-anchor="middle">${step === 24 ? `${tk.getMonth() + 1}/${tk.getDate()}` : tk.getHours() + "時"}</text>`;
    }
    const s = pts.slice().sort((a, b) => a.t - b.t);
    let d = "";
    s.forEach((p, i) => { d += i === 0 ? `M${x(p.t)} ${y(p.v)}` : `H${x(p.t)} V${y(p.v)}`; });
    d += `H${W}`;
    // 最新の値に印と数値を付ける（表示だけ。データは変えない）
    const last = s[s.length - 1], lv = Math.round(last.v * 10) / 10;
    const ly = y(last.v), lx = Math.min(W - 4, x(last.t));
    const mark = `<circle cx="${lx}" cy="${ly}" r="5" fill="${color}" stroke="var(--bg)" stroke-width="2"/><text x="${W - 8}" y="${ly < T + 20 ? ly + 22 : ly - 10}" class="lbl now" text-anchor="end">いま ${lv}${unit}</text>`;
    const aria = `${hours <= 24 ? "24時間" : hours <= 72 ? "3日間" : "7日間"}のグラフ。最新 ${lv}${unit}、範囲 ${lo}〜${hi}${unit}`;
    return `<svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="${esc(aria)}">${g}<path d="${d}" fill="none" stroke="${color}" stroke-width="2.5"/>${mark}</svg>`;
  }
  function summaryHTML(pts, unit) {
    const lo = pts.reduce((a, b) => (b.v < a.v ? b : a)), hi = pts.reduce((a, b) => (b.v > a.v ? b : a));
    const avg = pts.reduce((s, p) => s + p.v, 0) / pts.length;
    const f = (v) => (Math.round(v * 10) / 10).toFixed(1);
    return `<div class="note">最高 ${f(hi.v)}${unit}（${hm(hi.t)}）　最低 ${f(lo.v)}${unit}（${hm(lo.t)}）　平均 ${f(avg)}${unit}</div>`;
  }
  function renderThermoDialog() {
    const box = $("#thermoDlg");
    if (!box) return;
    const st = S.thermo, t = st && thermos().find((x) => x.key === st.key);
    box.classList.toggle("hidden", !t);
    if (!t) { box.innerHTML = ""; return; }
    const ch = (h, l) => `<button class="chip${st.hours === h ? " sel" : ""}" data-set="thermoHours" data-id="${h}">${l}</button>`;
    box.innerHTML = `<div class="dlg-bg" data-set="thermoClose"></div><div class="dlg"><div class="pagebar"><h3 style="flex:1;margin:0">🌡 ${esc(t.name)}</h3><button class="tbtn" data-set="thermoClose">閉じる</button></div>
      <div style="font-size:34px">${t.temp !== null ? fmt(Math.round(t.temp * 10) / 10) + "℃" : "－"} ${t.hum !== null ? `<span style="font-size:20px">💧${fmt(Math.round(t.hum))}%</span>` : ""}</div>
      <div class="chips">${ch(24, "24時間")}${ch(72, "3日")}${ch(168, "7日")}</div>
      ${st.status ? `<div class="note">${esc(st.status)}</div>` : ""}
      ${st.temp && st.temp.length >= 2 ? `<div class="fl">温度</div>${summaryHTML(st.temp, "℃")}${chartSVG(st.temp, st.hours, "#ff8a65", "℃")}` : ""}
      ${st.hum && st.hum.length >= 2 ? `<div class="fl">湿度</div>${summaryHTML(st.hum, "%")}${chartSVG(st.hum, st.hours, "#4fc3f7", "%")}` : ""}
      ${t.bat !== null ? `<div class="note">🔋 電池残り ${Math.round(t.bat)}%</div>` : ""}</div>`;
  }
  function thermoSettingsHTML() {
    const list = thermos(), hidden = store.get("thermoHidden", []), names = store.get("thermoNames", {});
    return `<div class="note">Home Assistant に取り込んだ温度計（SwitchBot 温湿度計など）の温度と湿度をホーム画面に表示します。同じ機器の温度と湿度は1つにまとめます。「✎ 名前」で部屋の名前などに変えられます。</div>
      ${list.length ? "" : `<div class="note">温度計が見つかりません。Home Assistant に SwitchBot の連携を追加すると、ここに表示されます。</div>`}
      ${list.map((t) => `<div class="row" style="cursor:default"><input type="checkbox" data-set="thermoVis" data-id="${esc(t.key)}" ${hidden.includes(t.key) ? "" : "checked"}>
        <span class="rt">${esc(t.name)}<div class="sub">${[t.temp !== null ? fmt(Math.round(t.temp * 10) / 10) + "℃" : null, t.hum !== null ? fmt(Math.round(t.hum)) + "%" : null, t.bat !== null ? "電池 " + Math.round(t.bat) + "%" : null].filter(Boolean).join("・") || "値なし"}${names[t.key] ? "・元の名前：" + esc(t.original) : ""}</div></span>
        <button class="tbtn" data-set="thermoRename" data-id="${esc(t.key)}">✎ 名前</button></div>`).join("")}
      <div id="thermoRenameBox"></div>`;
  }
  function thermoRenameHTML(key) {
    const t = thermos().find((x) => x.key === key);
    if (!t) return "";
    return `<div class="card" style="margin-top:8px"><div class="fl">「${esc(t.original)}」の名前</div>
      <input type="text" class="search" id="thermoName" value="${esc(t.name)}">
      ${rooms().length ? `<div class="note">部屋の名前から選ぶ</div><div class="chips">${rooms().map((r) => `<button class="chip" data-set="thermoRoomName" data-id="${esc(r.name)}">${esc(r.name)}</button>`).join("")}</div>` : ""}
      <div class="brow" style="margin-top:8px"><button class="btn" data-set="thermoNameSave" data-id="${esc(key)}">保存</button><button class="btn outline" data-set="thermoNameReset" data-id="${esc(key)}">元に戻す</button></div></div>`;
  }

  // ================= カレンダー（TimeTree など） =================
  const calendarEntities = () => (S.ha ? Object.values(S.ha.states).filter((e) => dom(e) === "calendar").sort((a, b) => nameOf(a).localeCompare(nameOf(b), "ja")) : []);
  const chosenCalendars = () => { const sel = store.get("calendars"); return calendarEntities().filter((c) => !sel || sel.includes(c.entity_id)); };
  async function loadEvents() {
    const cals = chosenCalendars();
    if (!cals.length) { S.events = []; renderCalendar(); return; }
    const start = new Date(); start.setHours(0, 0, 0, 0);
    const end = new Date(start.getTime() + 8 * 86400000);
    const all = [];
    for (const c of cals) {
      try {
        const arr = await restGet(`/api/calendars/${c.entity_id}?start=${encodeURIComponent(start.toISOString())}&end=${encodeURIComponent(end.toISOString())}`);
        (arr || []).forEach((e) => {
          const s = e.start || {}, en = e.end || {};
          const allDay = !s.dateTime && !!s.date;
          const st = allDay ? new Date(s.date + "T00:00:00") : new Date(s.dateTime);
          const et = allDay ? new Date((en.date || s.date) + "T00:00:00") : new Date(en.dateTime || s.dateTime);
          if (!isNaN(st)) all.push({ cal: nameOf(c), summary: e.summary || "（予定）", start: st, end: et, allDay });
        });
      } catch (e) { /* そのカレンダーは読めない */ }
    }
    S.events = all.sort((a, b) => a.start - b.start);
    renderCalendar();
  }
  function eventsOn(offset) {
    const day = new Date(); day.setHours(0, 0, 0, 0); day.setDate(day.getDate() + offset);
    const next = new Date(day.getTime() + 86400000);
    return (S.events || []).filter((e) => e.start < next && (e.allDay ? e.end > day : e.end > day || e.start >= day))
      .sort((a, b) => (b.allDay - a.allDay) || a.start - b.start);
  }
  const evTime = (e) => (e.allDay ? "終日" : `${e.start.getHours()}:${pad(e.start.getMinutes())}`);
  function renderCalendar() {
    const box = $("#cal");
    if (!box) return;
    if (!calendarEntities().length) { box.innerHTML = ""; return; }
    const col = (o, label) => {
      const list = eventsOn(o);
      return `<div class="calcol"><div class="fl">📅 ${label}</div>${list.length ? list.slice(0, 6).map((e) => `<div class="ev"><span>${evTime(e)}</span><span>${esc(e.summary)}</span></div>`).join("") : `<div class="note">予定はありません</div>`}</div>`;
    };
    box.innerHTML = `<div class="card calcard">${col(0, "今日")}${col(1, "明日")}</div>`;
  }
  function speakDay(offset) {
    const list = eventsOn(offset), label = offset === 0 ? "今日" : "明日";
    if (!list.length) return `${label}の予定はありません`;
    return `${label}の予定は${list.length}件です。` + list.map((e) => (e.allDay ? "終日" : `${e.start.getHours()}時${e.start.getMinutes() ? e.start.getMinutes() + "分" : ""}から`) + e.summary).join("、") + "です";
  }
  function calendarSettingsHTML() {
    const cals = calendarEntities(), sel = store.get("calendars");
    return `<div class="note">TimeTree の予定は、ホームパネルのサーバーが30分ごとに TimeTree から取り出し、Home Assistant の「Remote Calendar」で読み込んで表示します。<b>TimeTree のログイン情報はサーバーの中にだけ保存し、このアプリには保存しません。</b>このアプリが覚えるのは、どのカレンダーを表示するかだけです。</div>
      <div class="note">準備：① サーバーのセットアップをやり直す ② Proxmox のシェルで <code>pct enter 146</code> → <code>homepanel-calendar setup</code> ③ 表示された URL を Home Assistant の「Remote Calendar」に登録（名前は「TimeTree 家族」のように TimeTree から始める）</div>
      ${cals.length ? cals.map((c) => `<label class="row"><input type="checkbox" data-set="calSel" data-id="${esc(c.entity_id)}" ${!sel || sel.includes(c.entity_id) ? "checked" : ""}><span class="rt">${esc(nameOf(c))}<div class="sub">${esc(c.entity_id)}</div></span></label>`).join("")
        : `<div class="note">Home Assistant にカレンダーが見つかりません。</div>`}
      <div class="note">音声で「今日の予定は？」「明日の予定」と聞くと読み上げます。</div>`;
  }

  // ================= 今日の電気（エネルギーの収支） =================
  const unitFactor = (u) => ({ wh: 0.001, kwh: 1, mwh: 1000 }[String(u || "kwh").toLowerCase()] ?? 1);
  function parseEnergyPrefs(r) {
    const src = { gridIn: [], gridOut: [], solar: [], batOut: [], batIn: [], cost: [], devices: [] };
    (r.energy_sources || []).forEach((s) => {
      if (s.type === "grid") {
        (s.flow_from || []).forEach((f) => { if (f.stat_energy_from) src.gridIn.push(f.stat_energy_from); if (f.stat_cost) src.cost.push(f.stat_cost); });
        (s.flow_to || []).forEach((f) => { if (f.stat_energy_to) src.gridOut.push(f.stat_energy_to); });
        if (s.stat_energy_from) src.gridIn.push(s.stat_energy_from);
        if (s.stat_energy_to) src.gridOut.push(s.stat_energy_to);
      } else if (s.type === "solar" && s.stat_energy_from) src.solar.push(s.stat_energy_from);
      else if (s.type === "battery") { if (s.stat_energy_from) src.batOut.push(s.stat_energy_from); if (s.stat_energy_to) src.batIn.push(s.stat_energy_to); }
    });
    (r.device_consumption || []).forEach((d) => { if (d.stat_consumption) src.devices.push(d.stat_consumption); });
    return src;
  }
  async function loadEnergy() {
    if (!S.ha) return;
    try {
      const ha = S.ha;
      const src = parseEnergyPrefs(await ha.send({ type: "energy/get_prefs" }));
      if (!src.gridIn.length && !src.solar.length) { S.energy = null; renderEnergy(); return; }
      const ids = [...new Set([].concat(src.gridIn, src.gridOut, src.solar, src.batOut, src.batIn, src.cost))];
      const meta = await ha.send({ type: "recorder/get_statistics_metadata", statistic_ids: ids });
      const units = {};
      (meta || []).forEach((m) => { units[m.statistic_id] = m.statistics_unit_of_measurement; });
      const today = new Date(); today.setHours(0, 0, 0, 0);
      const res = await ha.send({ type: "recorder/statistics_during_period", start_time: today.toISOString(), end_time: new Date(Date.now() + 3600000).toISOString(), statistic_ids: ids, period: "hour", types: ["change"] });
      const sum = (list, costs) => list.reduce((s, id) => s + (res[id] || []).reduce((a, r) => a + (r.change || 0), 0) * (costs ? 1 : unitFactor(units[id])), 0);
      const t = { gridIn: sum(src.gridIn), gridOut: sum(src.gridOut), solar: sum(src.solar), batOut: sum(src.batOut), batIn: sum(src.batIn), cost: sum(src.cost, true) };
      t.used = Math.max(0, t.gridIn + t.solar + t.batOut - t.gridOut - t.batIn);
      t.self = t.used > 0.01 ? Math.max(0, Math.min(100, (1 - t.gridIn / t.used) * 100)) : null;
      S.energy = { t, hasSolar: src.solar.length > 0, hasExport: src.gridOut.length > 0 };
    } catch (e) { S.energy = null; }
    renderEnergy();
  }
  const kwh = (v) => (v >= 100 ? Math.round(v) : (Math.round(v * 10) / 10).toFixed(1)) + " kWh";
  function renderEnergy() {
    const box = $("#energy");
    if (!box) return;
    const en = S.energy;
    if (!en) { box.innerHTML = costStripHTML(); return; }
    const t = en.t;
    box.innerHTML = `<div class="card estrip"><b>⚡ 今日の電気</b><span>使用 <b>${kwh(t.used)}</b></span><span>買電 <b>${kwh(t.gridIn)}</b></span>
      ${en.hasExport ? `<span>売電 <b>${kwh(t.gridOut)}</b></span>` : ""}${en.hasSolar ? `<span>発電 <b>${kwh(t.solar)}</b></span>` : ""}
      ${t.self !== null && en.hasSolar ? `<span>自給率 <b>${Math.round(t.self)}%</b></span>` : ""}${t.cost > 0 && !(S.solar && S.solar.cost) ? `<span>電気代 <b>${Math.round(t.cost)}円</b></span>` : ""}</div>${costStripHTML()}`;
  }

  // ================= おひさま沸き上げ（サーバーで動いているものを表示） =================
  async function solarApi(path, body) {
    // サーバーは、Home Assistant にログインしている画面か、このトークンで確かめる
    const auth = { Authorization: "Bearer " + (await accessToken()) };
    const r = await fetch("/solar-api" + path, body ? { method: "POST", headers: Object.assign({ "Content-Type": "application/json" }, auth), body: JSON.stringify(body) } : { cache: "no-store", headers: auth });
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }
  async function loadSolar(path, body) {
    if (!SERVER_MODE) return;
    try { S.solar = await solarApi(path || "/status", body); S.solarErr = null; renderEnergy(); }
    catch (e) { if (path) S.solarErr = "サーバーに送れませんでした：" + e.message; else S.solar = null; }
    renderSolar();
    renderGroups();
    const sb = $("#ssBox");
    if (sb) sb.innerHTML = selfSuffHTML();
    const rb = $("#raBox");
    if (rb) rb.innerHTML = runAlertHTML();
  }

  // ================= 電気の自給率を上げる（どれもオプション。家のサーバーが見張って動かす） =================
  function ssChips(key, values, cur, fmtFn) {
    return `<div class="chips">${values.map((v) => `<button class="chip${Number(cur) === v ? " sel" : ""}" data-set="ssNum" data-id="${key}" data-v="${v}">${esc(fmtFn(v))}</button>`).join("")}</div>`;
  }
  const kwT = (v) => v + "kW";
  function ssHours(c, prefix, f, t) {
    return `<div class="note">始める時刻</div>${ssChips(prefix + "_from", [8, 9, 10, 11], c[prefix + "_from"] ?? f, (v) => v + "時から")}
      <div class="note">終わる時刻</div>${ssChips(prefix + "_to", [14, 15, 16, 17], c[prefix + "_to"] ?? t, (v) => v + "時まで")}`;
  }
  function ssOption(key, title, desc, on, body) {
    return `<div class="card" style="padding:12px;margin:8px 0"><label class="row" style="padding:0"><span class="rt"><b>${title}</b><div class="sub">${desc}</div></span>
      <input type="checkbox" data-set="ssBool" data-id="${key}" ${on ? "checked" : ""}></label>${on ? `<div style="margin-top:6px">${body}</div>` : ""}</div>`;
  }
  // ================= つけっぱなしのお知らせ（家のサーバーが見張る） =================
  const RUN_ALERT_DOMAINS = ["climate", "switch", "light", "fan", "humidifier", "media_player", "input_boolean", "water_heater"];
  function runAlertHTML() {
    const j = S.solar;
    if (!j || !j.config) return `<div class="note">家のサーバーから設定を読んでいます…</div>`;
    const c = j.config, now = j.run_alert_now || {};
    const items = c.run_alerts || [];
    const targets = c.demand_notify || [];
    const states = S.ha ? Object.values(S.ha.states) : [];
    const cands = states.filter((e) => RUN_ALERT_DOMAINS.includes(dom(e)) && !isElId(e.entity_id))
      .sort((a, b) => (items.some((x) => x.entity === b.entity_id) - items.some((x) => x.entity === a.entity_id)) || (dom(a) !== "climate") - (dom(b) !== "climate") || entName(a.entity_id).localeCompare(entName(b.entity_id), "ja"));
    return `<div class="note">選んだ機器が、決めた時間より長く続けて動いていたら、スマートフォンに知らせます（1回の運転につき1回）。家のサーバーが1分ごとに見張るので、この画面を閉じていても働きます。</div>
      ${c.ha_token_set ? "" : `<div class="warn">サーバーに Home Assistant の鍵の登録が必要です（Android 版の「エアコンの自動温度調整」から登録できます）。</div>`}
      <div class="${targets.length ? "note" : "warn"}">${targets.length ? "知らせる先：" + esc(targets.map((t) => t.replace("notify.mobile_app_", "")).join("、")) : "知らせる先がまだありません。Android 版の「お湯（エコキュート）と太陽光」→「知らせる先」で、スマートフォンを選んでください。"}</div>
      ${S.solarErr ? `<div class="warn">${esc(S.solarErr)}</div>` : ""}
      ${cands.slice(0, 60).map((e) => {
        const it = items.find((x) => x.entity === e.entity_id);
        const r = now[e.entity_id];
        return `<label class="row"><input type="checkbox" data-set="raToggle" data-id="${esc(e.entity_id)}" ${it ? "checked" : ""}>
            <span class="rt">${esc(entName(e.entity_id))}${r && r.running ? `<div class="sub" style="color:var(--primary)">いま ${Number(r.hours).toFixed(1)}時間 続けて動いています</div>` : ""}</span></label>
          ${it ? `<div style="margin-left:36px"><div class="note">この時間より長く動いていたら知らせる</div><div class="chips">${[1, 2, 3, 4, 6, 8, 12].map((v) => `<button class="chip${Number(it.hours) === v ? " sel" : ""}" data-set="raHours" data-id="${esc(e.entity_id)}" data-v="${v}">${v}時間</button>`).join("")}</div></div>` : ""}`;
      }).join("") || `<div class="note">機器が見つかりません</div>`}`;
  }

  function selfSuffHTML() {
    const j = S.solar;
    if (!j || !j.config) return `<div class="note">家のサーバーから設定を読んでいます…</div>`;
    const c = j.config;
    const states = S.ha ? Object.values(S.ha.states) : [];
    const acs = states.filter((e) => dom(e) === "climate" && !isElId(e.entity_id));
    const sws = states.filter((e) => ["switch", "input_boolean"].includes(dom(e)));
    const pickedAc = c.ss_aircon_entities || [];
    const items = c.ss_loads_items || [];
    const targets = c.demand_notify || [];
    return `<div class="note">太陽光の電気をできるだけ家の中で使い、買う電気を減らすための機能です。どれも使うかどうかを選べます（初めはすべてオフ）。家のサーバーが見張って動かすので、この画面を閉じていても働きます。</div>
      ${c.ha_token_set ? "" : `<div class="warn">エアコン・機器を動かす機能には、サーバーに Home Assistant の鍵の登録が必要です（Android 版の「エアコンの自動温度調整」から登録できます）。</div>`}
      ${S.solarErr ? `<div class="warn">${esc(S.solarErr)}</div>` : ""}
      ${ssOption("ss_show", "🌱 ホーム画面に今日の自給率を出す", "自給率＝使った電気のうち、買わずに済んだ割合。目標と比べて表示します。", c.ss_show,
        `<div class="note">目標</div>${ssChips("ss_target_pct", [30, 50, 70, 90], c.ss_target_pct ?? 50, (v) => v + "%")}`)}
      ${ssOption("ss_notify", "☀ 電気が余っているときに「いま使うとお得」と知らせる", "昼に売電が続いたら、洗濯機・食器洗い機・乾燥機などを回すよう、スマートフォンに知らせます（2時間に1回まで）。", c.ss_notify,
        `<div class="note">売電がこれ以上のとき</div>${ssChips("ss_notify_kw", [1, 1.5, 2, 3], c.ss_notify_kw ?? 1.5, kwT)}
         <div class="note">続いた時間</div>${ssChips("ss_notify_min", [5, 10, 20], c.ss_notify_min ?? 10, (v) => v + "分")}
         ${ssHours(c, "ss_notify", 9, 15)}
         <div class="${targets.length ? "note" : "warn"}">${targets.length ? "知らせる先：" + esc(targets.map((t) => t.replace("notify.mobile_app_", "")).join("、")) + "（30分ごとの電気の上限と同じ先）" : "知らせる先がまだありません。Android 版の「お湯（エコキュート）と太陽光」→「知らせる先」で、スマートフォンを選んでください。"}</div>`)}
      ${ssOption("ss_aircon", "❄ 電気が余っているときに、エアコンを先に動かす", "動いているエアコンだけ、冷房は少し下げ・暖房は少し上げて、夕方に買う電気を減らします。買う電気が増えたら元の温度に戻します（人が温度を変えていたら、そのまま）。エアコンを勝手に入れることはありません。", c.ss_aircon,
        `<div class="note">売電がこれ以上で10分続いたら</div>${ssChips("ss_aircon_kw", [0.5, 1, 1.5, 2], c.ss_aircon_kw ?? 1, kwT)}
         <div class="note">変える温度</div>${ssChips("ss_aircon_offset", [0.5, 1, 1.5, 2], c.ss_aircon_offset ?? 1, (v) => v + "℃")}
         ${ssHours(c, "ss_aircon", 10, 16)}
         <div class="note">対象のエアコン</div>
         ${acs.length ? acs.map((e) => `<label class="row"><input type="checkbox" data-set="ssAc" data-id="${esc(e.entity_id)}" ${pickedAc.includes(e.entity_id) ? "checked" : ""}><span class="rt">${esc(entName(e.entity_id))}</span></label>`).join("") : `<div class="note">エアコンが見つかりません</div>`}`)}
      ${ssOption("ss_loads", "🔌 電気が余っているときに動かす機器（電気自動車・蓄電池の充電など）", "売電が決めた量を超えて続いたら、選んだスイッチを入れます。買う電気が増えたら切ります（このしくみで入れたものだけ）。", c.ss_loads,
        `<div class="note">続いた時間</div>${ssChips("ss_loads_min", [5, 10, 20], c.ss_loads_min ?? 10, (v) => v + "分")}
         ${ssHours(c, "ss_loads", 9, 16)}
         <div class="note">動かす機器（スイッチ）</div>
         ${sws.length ? sws.slice(0, 40).map((e) => {
            const it = items.find((x) => x.entity === e.entity_id);
            return `<label class="row"><input type="checkbox" data-set="ssLoad" data-id="${esc(e.entity_id)}" ${it ? "checked" : ""}><span class="rt">${esc(entName(e.entity_id))}</span></label>
              ${it ? `<div style="margin-left:36px"><div class="note">売電がこれ以上で入れる（機器の消費電力くらい）</div>
                <div class="chips">${[1, 1.5, 3, 6].map((v) => `<button class="chip${it.on_kw === v ? " sel" : ""}" data-set="ssLoadKw" data-id="${esc(e.entity_id)}" data-f="on_kw" data-v="${v}">${v}kW</button>`).join("")}</div>
                <div class="note">買電がこれ以上で切る</div>
                <div class="chips">${[0.1, 0.3, 0.5, 1].map((v) => `<button class="chip${it.off_kw === v ? " sel" : ""}" data-set="ssLoadKw" data-id="${esc(e.entity_id)}" data-f="off_kw" data-v="${v}">${v}kW</button>`).join("")}</div></div>` : ""}`;
          }).join("") : `<div class="note">スイッチが見つかりません</div>`}`)}
      ${ssOption("ss_partial", "⛅ くもりの日も、昼に沸かせる分だけ夜の沸き上げを減らす", "晴れない予報の日でも、日射量の予報から昼に沸かせる割合（30%以上）を見積もり、その分だけ夜の沸き上げを減らします。「夜は止める」のときに働きます。足りなければ夕方の準備で沸かします。", c.ss_partial,
        c.night_method !== "stop" ? `<div class="warn">いまは「夜は止める」になっていないため、働きません。</div>` : "")}`;
  }
  const yenFmt = (v) => (v === null || v === undefined ? "－" : Math.round(v).toLocaleString("ja-JP") + "円");
  /** サーバーが電力会社のプランの単価で計算した電気代 */
  function costStripHTML() {
    const c = S.solar && S.solar.cost;
    if (!c) return "";
    return `<div class="card estrip"><b>💴 電気代</b><span>今日 <b>${yenFmt(c.today_yen)}</b></span><span>今月 <b>${yenFmt(c.month_yen)}</b></span>
      <span>見込み <b>${yenFmt(c.month_estimate_yen)}</b></span>${c.rate_now != null ? `<span>いまの単価 <b>${c.rate_now}円/kWh</b></span>` : ""}
      ${c.today_sell_yen > 0 ? `<span>売電 <b>${yenFmt(c.today_sell_yen)}</b></span>` : ""}<span class="note">${esc(c.plan || "")}</span></div>`;
  }
  /** 24時間のお湯：線＝残り、棒＝1時間ごとに使った量 */
  function hotChartHTML(hist, tank, hourly) {
    hist = hist || [];
    if (hist.length < 2 && !hourly) return "";
    const W = 640, H = 130, L = 62, B = 26, end = Date.now(), start = end - 86400000;
    const used = new Array(24).fill(0);
    for (let i = 1; i < hist.length; i++) {
      const [t1, r1] = hist[i], r0 = hist[i - 1][1];
      if (r1 < r0 && t1 * 1000 >= start) used[Math.min(23, Math.floor((t1 * 1000 - start) / 3600000))] += r0 - r1;
    }
    if (hourly) {
      // AiSEG2 の「湯」の画面の、1時間ごとの実際の使用量を使う
      const today = new Date().toDateString();
      for (let i = 0; i < 24; i++) {
        const t = new Date(start + i * 3600000); t.setMinutes(0, 0, 0);
        const list = (t.toDateString() === today ? hourly.today : hourly.yesterday) || [];
        used[i] = Number(list[t.getHours()] || 0);
      }
    }
    const maxL = Math.max(1, tank || Math.max(1, ...hist.map((p) => p[1]))), maxU = Math.max(10, ...used);
    const x = (t) => L + (W - L) * Math.max(0, Math.min(1, (t - start) / (end - start)));
    const y = (l) => H - B - (l / maxL) * (H - B - 4);
    let g = "";
    [maxL, maxL / 2, 0].forEach((v) => { g += `<line x1="${L}" x2="${W}" y1="${y(v)}" y2="${y(v)}" class="grid"/><text x="2" y="${y(v) + 4}" class="lbl">${Math.round(v)}L</text>`; });
    const bw = (W - L) / 24;
    used.forEach((u, i) => { if (u > 0) { const h = (u / maxU) * (H - B - 4) * 0.6; g += `<rect x="${L + i * bw + bw * 0.15}" y="${H - B - h}" width="${bw * 0.7}" height="${h}" fill="#42a5f5" opacity=".6"/>`; } });
    const tk = new Date(start); tk.setMinutes(0, 0, 0); tk.setHours(tk.getHours() + 1); while (tk.getHours() % 6) tk.setHours(tk.getHours() + 1);
    for (; tk.getTime() < end; tk.setHours(tk.getHours() + 6)) g += `<text x="${x(tk.getTime())}" y="${H - 4}" class="lbl" text-anchor="middle">${tk.getHours()}時</text>`;
    const d = hist.filter((p) => p[0] * 1000 >= start - 600000).map((p, i) => `${i ? "L" : "M"}${x(p[0] * 1000)} ${y(p[1])}`).join(" ");
    const total = Math.round(used.reduce((a, b) => a + b, 0));
    return `<div class="note">24時間のお湯（この24時間で約${total}L 使用）　<span style="color:var(--primary)">━ 残り</span>　<span style="color:#42a5f5">■ 使った量（1時間ごと）</span></div>
      <svg viewBox="0 0 ${W} ${H}" class="chart hot" role="img" aria-label="${esc(`24時間のお湯のグラフ。約${total}L 使用`)}">${g}<path d="${d}" fill="none" stroke="var(--primary)" stroke-width="2.5"/></svg>`;
  }
  function renderSolar() {
    const box = $("#solar");
    if (!box) return;
    const j = S.solar;
    if (!j || !j.config || !j.config.eco_ip) { box.innerHTML = ""; return; }
    const e = j.eco || {}, p = j.plan;
    const pct = e.remain && e.tank ? Math.min(100, Math.round(e.remain * 100 / e.tank)) : null;
    const t = (sec) => jstTime(sec * 1000);
    const w = j.surplus_w;
    box.innerHTML = `<div class="card solar"><div class="brow" style="align-items:center"><b style="flex:1">🛁 エコキュート（サーバーで管理）</b>
        <span class="${e.heating ? "fl" : "note"}">${!e.online ? "応答なし" : e.heating ? "♨ 沸き上げ中" : "沸き上げ停止中"}</span></div>
      ${e.remain != null ? `<div>残りのお湯 <b style="font-size:22px">${e.remain}L</b>${e.tank ? ` ／ ${e.tank}L（${pct}%）` : ""}</div>${pct !== null ? `<div class="bar"><div style="height:100%;width:${pct}%;background:var(--primary)"></div></div>` : ""}` : ""}
      <div class="note">☀ おひさま沸き上げ：${esc(j.config.enabled ? j.status : "オフ")}${w != null ? `（いま ${w >= 0 ? "売電" : "買電"} ${(Math.abs(w) / 1000).toFixed(1)}kW）` : ""}</div>
      ${p ? `<div class="${p.ok ? "note" : "warn"}">${p.message.startsWith("⚠") ? "" : p.ok ? "✓" : "✕"} 毎日の設定（${t(p.at)}）：${esc(p.message)}</div>` : ""}
      ${hotChartHTML(j.hot_history, e.tank, j.hot_hourly)}
      ${nightHeatPlanHTML(j)}
      ${awayHTML(j)}
      ${nightHeatHTML(j)}
      ${S.solarErr ? `<div class="warn">${esc(S.solarErr)}</div>` : ""}
      <div class="brow">${p && !p.ok ? `<button class="btn" data-set="solarRetry">もう一度設定</button>` : `<button class="tbtn" data-set="solarRetry">設定し直す</button>`}
        ${e.can_manual ? `<button class="btn outline" data-set="solarHeat" data-id="start">今すぐ沸き増し</button>${e.manual ? `<button class="tbtn" data-set="solarHeat" data-id="auto">自動に戻す</button>` : ""}` : ""}</div></div>`;
  }

  // ================= リアルタイム表示（発電・消費・売買電・エコキュート。10秒ごと） =================
  async function loadLive() {
    if (!SERVER_MODE) return;
    try { S.live = await solarApi("/live"); } catch (e) { S.live = null; }
    renderLive();
  }
  function renderLive() {
    const box = $("#live");
    if (!box) return;
    const v = S.live;
    if (!v) { box.innerHTML = ""; return; }
    const kw = (w) => (w / 1000).toFixed(1) + "kW";
    const e = v.eco || {};
    const away = v.away_until && v.away_until * 1000 > Date.now();
    const eco = away ? "🧳 留守" : e.online === false ? "応答なし" : e.heating ? "♨ 沸き上げ中" : e.heating === false ? "停止中" : "";
    const rest = e.remain != null ? ` 残り${e.remain}L${e.tank ? `（${Math.round(e.remain * 100 / e.tank)}%）` : ""}` : "";
    const d = v.demand;
    const over = d && d.warn_kw != null && d.projected_kw > d.warn_kw;
    const alert = v.demand_alert && Date.now() - v.demand_alert.at * 1000 < 30 * 60000 ? v.demand_alert.message : "";
    box.innerHTML = `<div class="card estrip live">
      ${v.generation_w != null ? `<span>☀ 発電 <b style="color:#43a047">${kw(v.generation_w)}</b></span>` : ""}
      ${v.consumption_w != null ? `<span>🏠 消費 <b style="color:#fb8c00">${kw(v.consumption_w)}</b></span>` : ""}
      ${v.surplus_w != null ? (v.surplus_w >= 0 ? `<span>⚡ 売電 <b style="color:#1e88e5">${kw(v.surplus_w)}</b></span>` : `<span>⚡ 買電 <b style="color:#e53935">${kw(-v.surplus_w)}</b></span>`) : ""}
      ${eco ? `<span>🛁 <b>${eco}</b>${rest}</span>` : ""}
      ${v.eco_today_kwh != null ? `<span>今日の沸き上げ <b>${Number(v.eco_today_kwh).toFixed(1)}kWh</b></span>` : ""}
      ${d && d.projected_kw != null ? `<span class="${over ? "warn" : "note"}">30分の電力（${esc(String(d.slot).slice(-5))}〜）：このままだと ${d.projected_kw.toFixed(1)}kW${d.warn_kw != null ? `（注意 ${d.warn_kw.toFixed(1)}kW・上限 ${d.limit_kw.toFixed(1)}kW）` : ""}</span>` : ""}
      ${ssRateHTML(v.selfsuff)}
      ${(v.auto_now || []).length ? v.auto_now.map((a) => `<span class="auto">${esc(a.icon)} 自動：${esc(a.text)}</span>`).join("")
        : v.heat_guard && v.heat_guard.active ? `<span class="warn">⏸ 買電が多いので、お湯の沸き上げを一時停止しています（${esc(v.heat_guard.reason)}）</span>` : ""}
      ${v.selfsuff && v.selfsuff.alert && Date.now() - v.selfsuff.alert.at * 1000 < 30 * 60000 ? `<span style="color:#43a047">☀ ${esc(v.selfsuff.alert.message)}</span>` : ""}
      ${alert ? `<span class="warn">${esc(alert)}</span>` : ""}</div>`;
  }
  function ssRateHTML(s) {
    if (!s || !s.show) return "";
    const r = s.rate, t = s.target || 0, ok = r != null && t > 0 && r >= t;
    return `<span class="ssrate">🌱 今日の自給率 <b style="color:${ok ? "#43a047" : "inherit"}">${r != null ? Math.round(r) + "%" : "－"}</b>
      ${t > 0 ? `<span class="bar" style="display:inline-block;width:120px;vertical-align:middle"><span style="display:block;height:100%;width:${Math.min(100, Math.round(((r || 0) / t) * 100))}%;background:#43a047"></span></span> 目標 ${t}%${ok ? " 達成" : ""}` : ""}</span>`;
  }

  // ================= まとめて操作（ボタン1つで、いくつもの機器をつける・消す。ボタンは Android 版で作る） =================
  function renderGroups() {
    const box = $("#groups");
    if (!box) return;
    const gs = (S.solar && S.solar.config && S.solar.config.groups) || [];
    if (!gs.length) { box.innerHTML = ""; return; }
    box.innerHTML = `<div class="brow groups">${gs.map((g) => `<button class="btn outline" data-set="groupRun" data-id="${esc(g.id)}" data-act="${esc(g.action)}">${esc(g.icon || (g.action === "on" ? "💡" : "⏻"))} ${esc(g.name)}</button>
      <button class="tbtn" data-set="groupRun" data-id="${esc(g.id)}" data-act="${g.action === "on" ? "off" : "on"}" title="反対の操作">${g.action === "on" ? "消す" : "つける"}</button>`).join("")}
      ${S.groupMsg ? `<span class="note">${esc(S.groupMsg)}</span>` : ""}</div>`;
  }
  async function runGroup(id, action) {
    const g = ((S.solar && S.solar.config && S.solar.config.groups) || []).find((x) => x.id === id);
    if (!g) return;
    S.groupMsg = `${g.name}：送っています…`; renderGroups();
    try {
      const r = await solarApi("/groups/run", { id, action });
      S.groupMsg = r.error || `${g.name}：${r.done}台を${action === "on" ? "つけ" : "消し"}ました${r.failed && r.failed.length ? `（できなかった：${r.failed.length}台）` : ""}`;
    } catch (e) { S.groupMsg = `${g.name}：送れませんでした（${e.message}）`; }
    renderGroups();
    setTimeout(() => { S.groupMsg = null; renderGroups(); }, 6000);
  }

  // ================= 留守（エコキュートの沸き上げを数日止める） =================
  function awayHTML(j) {
    const until = j.away_until ? j.away_until * 1000 : 0;
    if (until > Date.now()) {
      return `<div class="brow" style="align-items:center"><span class="fl" style="flex:1">🧳 留守：${jstTime(until)} に帰る予定（${j.away === "reheat" ? "帰る前なので沸かしています" : "沸き上げを止めています"}）</span>
        <button class="tbtn" data-set="awayOpen">変更</button><button class="tbtn danger" data-set="awayCancel">取り消す</button></div>${S.awayOpen ? awayFormHTML(j) : ""}`;
    }
    return S.awayOpen ? awayFormHTML(j) : `<div class="brow"><button class="btn outline" data-set="awayOpen">🧳 留守にする（沸き上げを止める）</button></div>`;
  }
  function awayFormHTML(j) {
    const cur = j.away_until && j.away_until * 1000 > Date.now() ? new Date(j.away_until * 1000) : new Date(Date.now() + 2 * 86400000);
    if (!(j.away_until && j.away_until * 1000 > Date.now())) cur.setHours(18, 0, 0, 0);
    const local = `${cur.getFullYear()}-${pad(cur.getMonth() + 1)}-${pad(cur.getDate())}T${pad(cur.getHours())}:${pad(cur.getMinutes())}`;
    const rh = (j.config && j.config.away_reheat_hours != null) ? j.config.away_reheat_hours : 6;
    return `<div class="card" style="margin-top:6px"><div class="note">帰る日時まで、エコキュートの沸き上げ（夜の沸き上げも）を止めます。帰る少し前から沸かし始めます。</div>
      <div class="brow" style="align-items:center">帰る日時 <input type="datetime-local" id="awayAt" value="${local}">
        帰る <select id="awayReheat">${[[0, "沸かさない"], [3, "3時間前"], [6, "6時間前"], [9, "9時間前"], [12, "12時間前"]].map(([h, l]) => `<option value="${h}"${Number(rh) === h ? " selected" : ""}>${l}</option>`).join("")}</select> から沸かす</div>
      <div class="brow"><button class="btn" data-set="awaySave">留守にする</button><button class="tbtn" data-set="awayClose">やめる</button></div>
      <div class="note">※ 留守の間に誰かがリモコンで沸き上げを戻しても、10分ごとに止め直します。</div></div>`;
  }

  // ================= 日本時間の表示 =================
  const JST = "Asia/Tokyo";
  const jstParts = (ms) => {
    const o = {};
    new Intl.DateTimeFormat("ja-JP", { timeZone: JST, year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit", weekday: "short", hourCycle: "h23" })
      .formatToParts(new Date(ms)).forEach((p) => { o[p.type] = p.value; });
    return o;
  };
  const jstTime = (ms) => { const p = jstParts(ms); return `${p.month}/${p.day} ${p.hour}:${p.minute}`; };
  const jstDay = (ms) => { const p = jstParts(ms); return `${p.month}/${p.day}（${p.weekday}）`; };
  const jstHM = (ms) => { const p = jstParts(ms); return `${p.hour}:${p.minute}`; };
  const dayLabel = (iso) => { const [y, m, d] = iso.split("-").map(Number); return `${m}/${d}（${"日月火水木金土"[new Date(y, m - 1, d).getDay()]}）`; };

  // ================= サーバーの機能（エアコンの自動温度調整・カメラの記録・操作の記録など） =================
  /** 記録の画像を、Authorization ヘッダー付きで読んで Blob の URL にする（前の URL は捨てる） */
  async function loadMotionFrame(mo) {
    const t = mo.sel;
    try {
      const r = await fetch(`/camera-api/frame?cam=${encodeURIComponent(mo.cam)}&t=${t}`, { cache: "no-store", headers: { Authorization: "Bearer " + (await accessToken()) } });
      if (!r.ok) throw new Error(r.status === 404 ? "この時刻の画像はありません" : "HTTP " + r.status);
      const url = URL.createObjectURL(await r.blob());
      if (S.motion !== mo || mo.sel !== t) { URL.revokeObjectURL(url); return; }
      if (mo.img) URL.revokeObjectURL(mo.img.url);
      mo.img = { t, url };
    } catch (e) {
      if (S.motion === mo && mo.sel === t) mo.img = { t, url: "", err: e.message };
    }
    if (S.motion === mo) fillExtra();
  }
  async function camApi(path, body) {
    const auth = { Authorization: "Bearer " + (await accessToken()) };
    const r = await fetch("/camera-api" + path, body ? { method: "POST", headers: Object.assign({ "Content-Type": "application/json" }, auth), body: JSON.stringify(body) } : { cache: "no-store", headers: auth });
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }
  const modeName = (m) => ({ cool: "冷房", heat: "暖房", dry: "除湿", fan_only: "送風", auto: "自動", heat_cool: "自動", off: "停止", unavailable: "つながっていません" }[m] || m || "不明");

  async function loadClimate() {
    if (!SERVER_MODE) return;
    try { S.climate = await solarApi("/climate"); S.climateErr = null; } catch (e) { S.climateErr = "サーバーから読めませんでした：" + e.message; }
    renderClimateBtn();
    if (S.page === "settings" && S.settingsPage === "climate") fillExtra();
  }
  function renderClimateBtn() {
    const b = $("#btnClimate");
    if (!b) return;
    const c = S.climate;
    const has = c && (c.rules || []).some((r) => r.enabled);
    b.classList.toggle("hidden", !has);
    if (has) b.innerHTML = `<span class="ti">❄</span><span class="tl"> 自動調整：${c.enabled ? "オン" : "オフ"}</span>`;
    b.style.opacity = c && c.enabled ? "1" : "0.6";
  }
  function climateHTML() {
    const c = S.climate;
    if (!SERVER_MODE) return `<div class="note">ホームパネルのサーバーから開いたときだけ使えます。</div>`;
    if (!c) return `<div class="note">${esc(S.climateErr || "読んでいます…")}</div>`;
    const rules = c.rules || [], st = c.state || {};
    const name = (eid) => (S.ha && S.ha.states[eid] ? nameOf(S.ha.states[eid]) : (st[eid] && st[eid].name) || eid);
    let h = `<div class="note">ホームパネルのサーバーが、部屋の温度を決めた範囲に保つよう、Home Assistant を通してエアコンを自動で冷房・暖房にします（1分ごと）。タブレットの電源が切れていても動き続けます。</div>
      <label class="row"><span class="rt"><b>自動温度調整（全体）</b><div class="sub">${c.enabled ? "オン：下のエアコンを自動で調整します" : "オフ：どのエアコンも自動では動かしません"}</div></span>
        <input type="checkbox" data-set="climEnabled" ${c.enabled ? "checked" : ""}></label>
      ${c.ha_token_set ? "" : `<div class="warn">サーバーに Home Assistant の鍵（トークン）が登録されていません。Android 版の「エアコンの自動温度調整」で「サーバーに鍵を登録」を押してください。</div>`}
      ${S.climateErr ? `<div class="warn">${esc(S.climateErr)}</div>` : ""}`;
    if (!rules.length) h += `<div class="note">まだエアコンの範囲が登録されていません。範囲の設定は Android 版の ⚙ 設定 →「エアコンの自動温度調整」で行います。</div>`;
    for (const r of rules) {
      const s = st[r.entity] || {};
      const open = S.climDetail === r.entity;
      h += `<div class="card"><div class="brow" style="align-items:center"><b style="flex:1">❄ ${esc(name(r.entity))}</b><span class="note">${r.enabled ? `${fmt(r.min)}〜${fmt(r.max)}℃` : "オフ"}</span>
          <button class="tbtn" data-set="climDetail" data-id="${esc(r.entity)}">${open ? "閉じる" : "詳しく"}</button></div>
        <div class="note">${esc(s.status || (r.enabled ? "確かめています…" : "この部屋の自動温度調整はオフです"))}</div>`;
      if (open) {
        const e = S.ha && S.ha.states[r.entity];
        const a = (e && e.attributes) || {};
        h += `<div class="note">運転：${esc(modeName(e ? e.state : s.mode))}${a.temperature != null ? `・設定 ${fmt(a.temperature)}℃` : ""}${a.current_temperature != null ? `・エアコンの室温 ${fmt(a.current_temperature)}℃` : ""}</div>
          <div class="note">温度のもと：${esc(s.source_name || (r.source ? r.source : "エアコンの室温"))}・${r.cool ? "冷房" : ""}${r.heat ? "・暖房" : ""}${r.off ? "・真ん中で停止" : ""}${s.at ? `・最後に確かめた時刻 ${jstTime(s.at * 1000)}` : ""}</div>`;
        const pts = (s.temps || []).filter((p) => p[1] != null).map((p) => ({ t: p[0] * 1000, v: p[1] }));
        h += pts.length >= 2 ? `<div class="fl">24時間の温度（範囲 ${fmt(r.min)}〜${fmt(r.max)}℃）</div>${chartSVG(pts, 24, "#4fc3f7", "℃")}` : `<div class="note">温度の記録はまだありません（10分ごとに記録します）</div>`;
        const acts = s.actions || [];
        h += `<div class="fl">自動で行った操作</div>` + (acts.length ? acts.slice(0, 20).map((x) => `<div class="note">${jstTime(x[0] * 1000)}　${esc(x[1])}</div>`).join("") : `<div class="note">まだありません</div>`);
      }
      h += `</div>`;
    }
    return h;
  }

  // ---- エコキュート：夜に沸かす量・日ごとのお湯 ----
  // ---- お湯の量を過去の使用量から自動で決める（夕方の準備・多めの日） ----
  function hotDay(iso) {
    const t = new Date(); const td = `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`;
    const tm = new Date(t.getTime() + 86400000); const tmd = `${tm.getFullYear()}-${pad(tm.getMonth() + 1)}-${pad(tm.getDate())}`;
    return iso === td ? "今日" : iso === tmd ? "明日" : dayLabel(iso);
  }
  function hotNeedText(n) {
    const parts = [n.predicted != null ? `予想 ${n.predicted}L` : null, n.margin ? `余裕 ${n.margin}L` : null,
      n.extra ? `多めの日 ${n.extra}L${n.note ? `（${esc(n.note)}）` : ""}` : null, n.loss ? `冷めて減る分 ${n.loss}L` : null].filter(Boolean);
    return parts.join(" ＋ ") + (n.need != null ? ` ＝ ${n.need}L` : "") + (n.predicted == null ? `（${esc(n.how || "")}）` : "");
  }
  /** 今夜の沸き上げの予定（できるだけ朝に近い時刻） */
  function nightHeatPlanHTML(j) {
    const nh = j.hot_plan && j.hot_plan.night_heat;
    if (!nh) return "";
    const pl = nh.plan || {};
    if (nh.started) return `<div class="fl">🌙 夜の沸き上げ中（${nh.target}L まで）</div>`;
    const save = pl.saving_yen || 0;
    return `<div class="fl">🌙 今夜は ${jstHM(nh.start * 1000)} から沸かします（約${pl.liters}L・約${pl.hours}時間・目標 ${nh.target}L）</div>
      ${pl.yen != null ? `<div class="note">見込みの電気代 約${Math.round(pl.yen)}円${save >= 0.5 ? `（夜の始まりから沸かすより約${Math.round(save)}円安い）` : ""}・朝までに冷める熱 約${pl.loss_kwh}kWh・その時刻の外気温 ${pl.t_out}℃</div>` : ""}`;
  }
  function hotPlanHTML(j) {
    const c = j.config || {}, p = j.hot_plan || {};
    const chip = (sel, set, id, label) => `<button class="chip${sel ? " sel" : ""}" data-set="${set}" data-id="${id}">${label}</button>`;
    const g = j.heat_guard || {};
    const lateRow = c.night_method === "stop" ? `<label class="row" style="margin-top:6px"><span class="rt">🌙 夜の沸き上げは、できるだけ朝に近い時刻にする<div class="sub">沸かしたお湯が朝までに冷める分（外気との温度差に比例）と、その時刻の電気料金・外気温での効率から、いちばん安い開始時刻を選びます（差が1円以内なら遅いほう）。</div></span>
      <input type="checkbox" data-set="nightLate" ${c.night_late !== false ? "checked" : ""}></label>` : "";
    let h = lateRow + `<label class="row" style="margin-top:6px"><span class="rt">⏸ 買電が多いときはお湯を沸かさない<div class="sub">買電が目安（${g.level_kw != null ? g.level_kw + "kW・" + esc(g.level_how || "") : "自動"}）以上か、30分の電力が注意の値を超えそうなときは沸き上げを一時停止し、下がったら戻します（最長1時間・お湯が20%未満なら止めません）。目安は Android 版で変えられます。${g.active ? `<br><b>いま一時停止中（${esc(g.reason)}）</b>` : ""}</div></span>
      <input type="checkbox" data-set="heatGuard" ${c.heat_guard !== false ? "checked" : ""}></label>`;
    h += `<label class="row" style="margin-top:6px"><span class="rt">お湯の量を過去の使用量から自動で決める<div class="sub">直近4週間の同じ種類の日（平日／土日祝）から夜に使う量を予想し、夕方までに「予想＋余裕」を用意します。足りなければ間に合う時刻から沸かします。</div></span>
      <input type="checkbox" data-set="hotAuto" ${c.hot_auto ? "checked" : ""}></label>`;
    if (!c.hot_auto) return h;
    const m = c.hot_margin_l ?? 50, ph = c.prep_hour ?? 17;
    h += `<div class="fl">余裕（予想に足す量）：${m}L</div><div class="chips">${[0, 50, 100, 150, 200, 250, 300, 400].map((v) => chip(v === m, "hotMargin", v, v + "L")).join("")}</div>
      <div class="brow" style="align-items:center">${numInputHTML("hotMarginIn", m, "L", "")}</div>
      <div class="fl">何時までに用意するか：${ph}時</div><div class="chips">${[15, 16, 17, 18, 19, 20].map((v) => chip(v === ph, "prepHour", v, v + "時")).join("")}</div>`;
    const e = p.evening, n = p.night, st = p.prep || {};
    if (e) h += `<div class="note" style="color:var(--primary)">🛁 ${hotDay(e.date)}の${e.from}時に残しておく（今夜の沸き上げ ${e.to > 24 ? "翌" + (e.to - 24) : e.to}時まで使う分）：${hotNeedText(e)}${e.max != null ? `（多い日は ${e.max}L・${esc(e.how)}）` : ""}</div>`;
    if (p.day_store && p.day_store.on && p.day_store.target != null) h += `<div class="note">☀ 昼に余った電気では ${p.day_store.target}L まで沸かします（${esc(p.day_store.how)}）</div>`;
    if (st.heating) h += `<div class="fl">♨ 夕方の準備のために沸かしています</div>`;
    else if (st.short > 0 && st.start_at && !st.done) h += `<div class="note">今のままだと ${st.short}L 足りないので、${st.start_at} ごろから沸かします（昼に余った電気で沸けば不要）</div>`;
    if (n) h += `<div class="note">${hotDay(n.date)}の朝（0時〜${n.to}時）：${hotNeedText(n)}${n.pct != null ? ` → 夜を減らす日は ${n.pct}% まで沸かします` : ""}</div>`;
    const l = p.learn;
    if (l) {
      h += `<label class="row" style="margin-top:6px"><span class="rt">🌡 気温を見込んで沸かす量を決める<div class="sub">寒い日にお湯を多く使う度合いと、外気との温度差で冷めて減る量を記録から学び、翌日の気温の予報で予想を直します。</div></span>
        <input type="checkbox" data-set="hotTempLearn" ${l.enabled ? "checked" : ""}></label>`;
      if (l.enabled) h += `<div class="note">${l.usage_per_deg != null ? `平均気温が1℃下がると、1日に約${Math.round(l.usage_per_deg)}L 多く使う（${l.usage_days}日分から）` : "お湯の使い方は学習中です"}・冷めて減る量 約${l.loss_k}L/時/℃（${esc(l.loss_how)}）${l.tomorrow_t != null ? `・明日の予想 ${l.tomorrow_t}℃ → 使う量の予想 ${l.tomorrow_factor >= 1 ? "+" : ""}${Math.round((l.tomorrow_factor - 1) * 100)}%` : ""}</div>`;
    }
    const ex = p.extra || [];
    h += `<div class="fl">多めにお湯を使う日</div>${ex.length ? ex.map((x, i) => `<div class="brow" style="align-items:center"><span style="flex:1">${hotDay(x.date)}${x.part === "morning" ? "の朝" : "の夕方〜夜"} +${x.liters}L${x.note ? `（${esc(x.note)}）` : ""}</span><button class="tbtn" data-set="hotExtraDel" data-id="${i}">取り消す</button></div>`).join("") : `<div class="note">ありません</div>`}`;
    if (S.hotExtraOpen) {
      const t = new Date(Date.now() + 86400000);
      h += `<div class="card" style="margin-top:6px"><div class="brow" style="align-items:center">日 <input type="date" id="hxDate" value="${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}">
          <select id="hxPart"><option value="evening">夕方〜夜（お風呂など）</option><option value="morning">朝</option></select>
          <select id="hxLiters">${[50, 100, 150, 200, 300].map((v) => `<option value="${v}"${v === 100 ? " selected" : ""}>+${v}L</option>`).join("")}</select>
          <input type="text" id="hxNote" placeholder="メモ（例：来客）" maxlength="30" style="width:140px"></div>
        <div class="brow"><button class="btn" data-set="hotExtraSave">追加</button><button class="tbtn" data-set="hotExtraClose">やめる</button></div>
        <div class="note">※ 浴槽のお湯はりは1回で約180〜200Lです。</div></div>`;
    } else h += `<div class="brow"><button class="btn outline" data-set="hotExtraOpen">🛁 多めにお湯を使う日を指定する</button></div>`;
    return h;
  }
  async function saveHotExtra(list) {
    await loadSolar("/config", { hot_extra: list });
  }

  function nightHeatHTML(j) {
    const c = j.config || {}, e = j.eco || {};
    if (c.night_method !== "stop") return hotPlanHTML(j);
    const tank = e.tank || (Object.values(j.daily || {}).reverse().find((d) => d.hot_tank_l) || {}).hot_tank_l;
    const lit = (p) => (tank ? `${p}%（約${Math.round(tank * p / 100)}L）` : `${p}%`);
    const cur = c.night_heat_pct ?? 50;
    const days = Object.entries(j.daily || {}).filter(([, v]) => v.hot_use_l != null || v.hot_heat_l != null).slice(-7);
    const max = Math.max(1, ...days.map(([, v]) => Math.max(v.hot_use_l || 0, v.hot_heat_l || 0)));
    const avg = days.length ? days.reduce((s, [, v]) => s + (v.hot_use_l || 0), 0) / days.length : null;
    return hotPlanHTML(j) + `<div class="fl" style="margin-top:6px">${c.hot_auto ? "記録が無いときに夜に沸かす量" : "晴れの日の夜に沸かす量"}：${lit(cur)}${j.night_pending ? "（いま夜の沸き上げ中）" : j.night_stop ? "（いま夜の沸き上げを止めています）" : ""}</div>
      <div class="chips">${[0, 20, 30, 40, 50, 60, 70, 80].map((p) => `<button class="chip${p === cur ? " sel" : ""}" data-set="nightPct" data-id="${p}">${p}%${tank ? "・" + Math.round(tank * p / 100) + "L" : ""}</button>`).join("")}</div>
      ${days.length ? `<details><summary class="fl" style="cursor:pointer">直近の日ごとのお湯（参考・1日平均 約${Math.round(avg)}L）</summary>` + days.map(([d, v]) => `<div class="hwrow"><span style="width:84px">${dayLabel(d)}</span>
        <span class="hwbars"><span class="hwb" style="width:${Math.max(1, (v.hot_use_l || 0) * 100 / max)}%"></span><span class="hwb h" style="width:${Math.max(1, (v.hot_heat_l || 0) * 100 / max)}%"></span></span>
        <span class="note">使った ${Math.round(v.hot_use_l || 0)}L${tank ? `（${Math.round((v.hot_use_l || 0) * 100 / tank)}%）` : ""}・沸かした ${Math.round(v.hot_heat_l || 0)}L</span></div>`).join("")
        + `<div class="note">1日の平均の使用量：約${Math.round(avg)}L${tank ? `（タンクの${Math.round(avg * 100 / tank)}%）` : ""}。夜に沸かす量は、これより少し多めにしておくと曇っても湯切れしにくくなります。</div></details>` : ""}`;
  }

  // ---- カメラの記録と動き ----
  async function loadCamRec() {
    try { S.camRec = await camApi("/status"); S.camRecErr = null; } catch (e) { S.camRecErr = "サーバーから読めませんでした：" + e.message; }
    fillExtra();
  }
  async function loadMotion(cam) {
    S.motion = { cam, data: null, sel: null };
    fillExtra();
    try { S.motion.data = await camApi(`/motion?cam=${encodeURIComponent(cam)}&hours=48`); } catch (e) { S.motion.err = e.message; }
    fillExtra();
  }
  const camIdOf = (eid) => eid.split(".").slice(1).join(".").replace(/[^A-Za-z0-9_]/g, "_");
  function motionSVG(m, th, sel) {
    const W = 640, H = 156, B = 26, end = Date.now(), start = end - 48 * 3600000, bin = 600000, n = 48 * 6;
    const bins = new Array(n).fill(-1);
    m.forEach(([t, v]) => { const i = Math.floor((t * 1000 - start) / bin); if (i >= 0 && i < n) bins[i] = Math.max(bins[i], v); });
    const top = Math.max(1, Math.min(th * 6, Math.max(th * 3, ...bins)));
    const x = (t) => (W * (t - start)) / (end - start);
    let g = "";
    // 夜（19時〜翌7時）の帯
    for (let d = new Date(start - 86400000); d.getTime() < end; d.setDate(d.getDate() + 1)) {
      const a = new Date(d); a.setHours(19, 0, 0, 0); const b = new Date(a.getTime() + 12 * 3600000);
      const xa = x(Math.max(start, a.getTime())), xb = x(Math.min(end, b.getTime()));
      if (xb > xa) g += `<rect x="${xa}" y="0" width="${xb - xa}" height="${H - B}" class="night"/>`;
    }
    const bw = W / n;
    bins.forEach((v, i) => {
      if (v < 0) return;
      const h = Math.max(0.02, Math.min(1, v / top)) * (H - B);
      const id = Math.round((start + i * bin + bin / 2) / 1000);
      g += `<rect x="${i * bw}" y="${H - B - h}" width="${Math.max(1, bw * 0.85)}" height="${h}" class="${v >= th ? "mv" : "mv weak"}" data-set="motionPick" data-id="${id}"/>`;
      // 細い棒でも押しやすいように、上下いっぱいの透明な押せる範囲を重ねる（同じ操作）
      g += `<rect x="${i * bw}" y="0" width="${bw}" height="${H - B}" class="mvhit" data-set="motionPick" data-id="${id}"/>`;
    });
    const yt = H - B - (th / top) * (H - B);
    g += `<line x1="0" x2="${W}" y1="${yt}" y2="${yt}" class="grid"/>`;
    const tk = new Date(start); tk.setMinutes(0, 0, 0); tk.setHours(tk.getHours() + (6 - (tk.getHours() % 6)));
    for (; tk.getTime() < end; tk.setHours(tk.getHours() + 6)) g += `<text x="${x(tk.getTime())}" y="${H - 4}" class="lbl" text-anchor="middle">${tk.getHours() === 0 ? `${tk.getMonth() + 1}/${tk.getDate()}` : tk.getHours() + "時"}</text>`;
    if (sel) g += `<line x1="${x(sel * 1000)}" x2="${x(sel * 1000)}" y1="0" y2="${H - B}" stroke="var(--error)" stroke-width="2"/>`;
    return `<svg viewBox="0 0 ${W} ${H}" class="chart motion" role="img" aria-label="48時間の動きのグラフ。棒を押すとその時刻の録画を表示">${g}</svg>`;
  }
  function camRecHTML() {
    if (!SERVER_MODE) return `<div class="note">ホームパネルのサーバーから開いたときだけ使えます。</div>`;
    const s = S.camRec;
    let h = `<div class="note">選んだカメラの画像を、ホームパネルのサーバーが1分ごとに保存し（2日分。古いものは自動で消します）、動きの量を記録します。動きのある時間・ない時間と、夜に動きがなくなった時刻（寝た時刻の目安）が分かります。</div>`;
    if (S.camRecErr) h += `<div class="warn">${esc(S.camRecErr)}</div>`;
    if (!s) return h + `<div class="note">読んでいます…</div>`;
    if (!s.ffmpeg) h += `<div class="warn">サーバーに ffmpeg が入っていません。サーバーのセットアップ（setup.sh）をやり直してください。</div>`;
    if (!s.ha_token_set) h += `<div class="note">※ Home Assistant のカメラを記録するには、Android 版の「カメラ」設定で「サーバーに鍵を登録」を押してください。</div>`;
    const rec = new Map((s.cameras || []).map((c) => [c.id, c]));
    const haCams = S.ha ? Object.values(S.ha.states).filter((e) => dom(e) === "camera").sort((a, b) => nameOf(a).localeCompare(nameOf(b), "ja")) : [];
    const rows = haCams.map((e) => ({ id: camIdOf(e.entity_id), name: nameOf(e), entity: e.entity_id, kind: "ha" }));
    (s.cameras || []).forEach((c) => { if (!rows.some((r) => r.id === c.id)) rows.push({ id: c.id, name: c.name, entity: c.entity, kind: c.kind }); });
    for (const r of rows) {
      const c = rec.get(r.id), st = c && c.status;
      h += `<div class="card"><label class="row" style="padding:0"><span class="rt"><b>📷 ${esc(r.name)}</b><div class="sub">${r.kind === "rtsp" ? "直接つなぐカメラ（Android 版で登録）" : "Home Assistant のカメラ"}</div></span>
          <input type="checkbox" data-set="camRecToggle" data-id="${esc(r.id)}" data-entity="${esc(r.entity || "")}" data-name="${esc(r.name)}" ${c ? "checked" : ""} ${r.kind === "rtsp" && !c ? "disabled" : ""}></label>
        ${c ? `<div class="note">${!st ? "記録を待っています" : st.error ? "✕ 記録できません：" + esc(st.error) : `✓ 記録中・最後に記録した時刻 ${jstHM(st.at * 1000)}・動きの量 ${st.score}%`}</div>
          <div class="brow"><button class="btn outline" data-set="motionOpen" data-id="${esc(r.id)}">🎞 動きを見る</button></div>` : ""}`;
      const mo = S.motion;
      if (mo && mo.cam === r.id) {
        const d = mo.data;
        if (mo.err) h += `<div class="warn">${esc(mo.err)}</div>`;
        else if (!d) h += `<div class="note">読んでいます…</div>`;
        else {
          h += `<div class="fl">寝た・起きた時刻の目安</div>` + ((d.sleep || []).length ? d.sleep.map((n) => `<div style="font-size:17px">🌙 ${dayLabel(n.night)}の夜：寝た ${n.slept || "－"}　☀ 起きた ${n.woke || "－"}</div>`).join("") : `<div class="note">まだ分かりません（夜の記録がたまると表示します）</div>`);
          h += (d.motion || []).length ? motionSVG(d.motion, d.threshold || 2, mo.sel) + `<div class="note">濃い棒＝動きあり（${d.threshold}% 以上）、灰色の帯＝夜（19時〜7時）。棒をタップすると、その時刻の画像を出します。</div>` : `<div class="note">まだ記録がありません</div>`;
          // 画像は Authorization ヘッダー付きで読み、Blob の URL で表示する（URL にトークンを載せない）
          if (mo.sel) h += `<div class="fl">${jstTime(mo.sel * 1000)} ごろの画像</div>${mo.img && mo.img.t === mo.sel ? (mo.img.err ? `<div class="warn">${esc(mo.img.err)}</div>` : `<img class="motionimg" alt="" src="${mo.img.url}">`) : `<div class="note">画像を読んでいます…</div>`}`;
        }
      }
      h += `</div>`;
    }
    return h;
  }

  // ---- 操作の記録 ----
  async function loadOpLog() {
    S.oplog = { hours: (S.oplog && S.oplog.hours) || 24, kind: (S.oplog && S.oplog.kind) || "all", rows: null };
    fillExtra();
    try {
      const names = {};
      Object.values(S.ha.states).filter((e) => dom(e) === "person" && e.attributes.user_id).forEach((p) => { names[p.attributes.user_id] = nameOf(p); });
      if (S.user && S.user.isAdmin) {
        try { (await S.ha.send({ type: "config/auth/list" })).forEach((u) => { names[u.id] = names[u.id] || u.name; }); } catch (e) { /* 名前は人から */ }
      }
      const start = new Date(Date.now() - S.oplog.hours * 3600000).toISOString();
      const arr = await restGet("/api/logbook/" + encodeURIComponent(start));
      const tr = (m) => ({ "turned on": "オンにしました", "turned off": "オフにしました", triggered: "実行しました", opened: "開けました", closed: "閉めました" }[(m || "").trim()] || String(m || "").replace("changed to ", "→ ").replace("triggered by ", "きっかけ："));
      const rows = [];
      for (const e of arr || []) {
        let kind, who;
        if (e.context_user_id) { kind = "user"; who = names[e.context_user_id] || "ユーザー"; }
        else if (e.context_event_type === "automation_triggered") { kind = "auto"; who = "自動化" + (e.context_name ? `「${e.context_name}」` : ""); }
        else if (e.context_event_type === "script_started") { kind = "auto"; who = "スクリプト" + (e.context_name ? `「${e.context_name}」` : ""); }
        else if (e.domain === "automation") { kind = "auto"; who = "自動化"; }
        else continue;
        rows.push({ at: Date.parse(e.when), who, kind, what: `${e.name || e.entity_id}：${tr(e.message || e.state)}` });
      }
      try {
        const s = await solarApi("/status");
        const now = Date.now(), y = new Date().getFullYear();
        (s.log || []).forEach((l) => {
          const m = /^(\d{2})\/(\d{2}) (\d{1,2}):(\d{2})[\s　]*(.*)$/.exec(l);
          if (!m) return;
          let t = new Date(y, +m[1] - 1, +m[2], +m[3], +m[4]).getTime();
          if (t > now + 86400000) t = new Date(y - 1, +m[1] - 1, +m[2], +m[3], +m[4]).getTime();
          if (t >= now - S.oplog.hours * 3600000) rows.push({ at: t, who: "サーバー", kind: "server", what: m[5] });
        });
      } catch (e) { /* サーバーなし */ }
      S.oplog.rows = rows.sort((a, b) => b.at - a.at);
    } catch (e) { S.oplog.err = e.message; }
    fillExtra();
  }
  function opLogHTML() {
    const o = S.oplog || { hours: 24, kind: "all" };
    const ch = (set, v, cur, l) => `<button class="chip${cur === v ? " sel" : ""}" data-set="${set}" data-id="${v}">${l}</button>`;
    let h = `<div class="note">Home Assistant の記録から、人・自動化が操作したものと、ホームパネルのサーバーが行った操作を新しい順に表示します。</div>
      <div class="chips">${ch("opHours", 24, o.hours, "24時間")}${ch("opHours", 72, o.hours, "3日")}${ch("opHours", 168, o.hours, "7日")}　
        ${ch("opKind", "all", o.kind, "すべて")}${ch("opKind", "user", o.kind, "👤 人")}${ch("opKind", "auto", o.kind, "⚙ 自動化")}${ch("opKind", "server", o.kind, "🖥 サーバー")}</div>`;
    if (o.err) return h + `<div class="warn">読めませんでした：${esc(o.err)}</div>`;
    if (!o.rows) return h + `<div class="note">読んでいます…</div>`;
    const rows = o.rows.filter((r) => o.kind === "all" || r.kind === o.kind);
    if (!rows.length) h += `<div class="note">この期間の操作はありません</div>`;
    let last = "";
    rows.slice(0, 400).forEach((r) => {
      const d = jstDay(r.at);
      if (d !== last) { h += `<div class="fl" style="margin-top:8px">${d}</div>`; last = d; }
      h += `<div class="oprow"><span class="opt">${jstHM(r.at)}</span><span class="opw">${{ user: "👤 ", server: "🖥 " }[r.kind] || "⚙ "}${esc(r.who)}</span><span>${esc(r.what)}</span></div>`;
    });
    return h + `<div class="note">※ 人の名前は Home Assistant のユーザー（人に結びついたもの）です。</div>`;
  }

  // ---- アカウントの一覧 ----
  async function loadAccounts() {
    S.acc = { solar: null, cam: null, users: null };
    try { S.acc.solar = await solarApi("/status"); } catch (e) { /* なし */ }
    try { S.acc.cam = await camApi("/status"); } catch (e) { /* なし */ }
    if (S.user && S.user.isAdmin) { try { S.acc.users = await S.ha.send({ type: "config/auth/list" }); } catch (e) { /* なし */ } }
    fillExtra();
  }
  function accountListHTML() {
    const a = S.acc;
    if (!a) return `<div class="note">読んでいます…</div>`;
    const line = (l, v, ok) => `<div class="accrow"><span class="accl">${esc(l)}</span><span>${ok ? "✓ " : "－ "}${esc(v)}</span></div>`;
    const c = (a.solar && a.solar.config) || {};
    let h = `<div class="note">このアプリとホームパネルのサーバーが使っているアカウントです。パスワードや鍵の中身は表示しません。</div>
      <div class="card"><div class="fl">📱 このアプリ（Home Assistant）</div>${line("普段使いのユーザー", S.user ? S.user.name + (S.user.isAdmin ? "（管理者）" : "（管理者以外）") : "確認中", !!S.user)}
        ${line("管理者ログイン", store.get("admin_user") || (S.user && S.user.isAdmin ? "普段使いのユーザーが管理者" : "なし"), !!store.get("admin_user") || (S.user && S.user.isAdmin))}</div>
      <div class="card"><div class="fl">🖥 ホームパネルのサーバー</div>
        ${a.solar || a.cam ? line("Home Assistant の鍵（温度調整）", c.ha_token_set ? "登録あり" : "なし", c.ha_token_set) + line("Home Assistant の鍵（カメラの記録）", a.cam && a.cam.ha_token_set ? "登録あり" : "なし", a.cam && a.cam.ha_token_set)
          + line("AiSEG2", c.aiseg_url ? `${c.aiseg_url}・パスワード${c.aiseg_password_set ? "登録あり" : "なし"}` : "なし", !!c.aiseg_url) + line("記録しているカメラ", ((a.cam && a.cam.cameras) || []).length + "台", ((a.cam && a.cam.cameras) || []).length > 0)
          : `<div class="note">サーバーにつながりません</div>`}</div>`;
    const tt = S.ha ? Object.keys(S.ha.states).filter((k) => k.startsWith("calendar.timetree")).length : 0;
    h += `<div class="card"><div class="fl">📅 その他のサービス</div>${line("TimeTree", tt ? `サーバーで取り込み → Home Assistant（カレンダー${tt}個）` : "なし", tt > 0)}</div>`;
    h += `<div class="card"><div class="fl">👥 Home Assistant のユーザー</div>`;
    if (!a.users) h += `<div class="note">ユーザーの一覧は、管理者のユーザーで使っているときに表示します（Android 版では管理者ログインでも見られます）。</div>`;
    else {
      const people = a.users.filter((u) => !u.system_generated);
      h += people.map((u) => line(u.name, [u.is_owner ? "所有者" : null, (u.group_ids || []).includes("system-admin") ? "管理者" : "ユーザー", u.local_only ? "家の中だけ" : null, u.is_active === false ? "無効" : null, u.username ? "ログイン名：" + u.username : null].filter(Boolean).join("・"), u.is_active !== false)).join("");
      if (a.users.length > people.length) h += `<div class="note">ほかに、Home Assistant が自動で作ったユーザーが${a.users.length - people.length}個あります。</div>`;
    }
    return h + `</div>`;
  }

  /** 設定ページのうち、サーバーから読むものを描き直す */
  function fillExtra() {
    const box = $("#extraBox");
    if (!box) return;
    const p = S.settingsPage;
    box.innerHTML = p === "climate" ? climateHTML() : p === "camrec" ? camRecHTML() : p === "oplog" ? opLogHTML() : p === "account" ? accountListHTML() : "";
  }
  function afterSettingsOpen() {
    const p = S.page === "settings" && S.settingsPage;
    if (p === "selfsuff" || p === "runalert") loadSolar();
    if (p === "climate") loadClimate();
    else if (p === "camrec") loadCamRec();
    else if (p === "oplog") loadOpLog();
    else if (p === "account") loadAccounts();
  }
  async function onServerSetting(el) {
    const id = el.dataset.id;
    switch (el.dataset.set) {
      case "climEnabled":
        try { await solarApi("/config", { climate_enabled: el.checked }); } catch (e) { S.climateErr = "サーバーに送れませんでした：" + e.message; }
        loadClimate(); return true;
      case "climDetail": S.climDetail = S.climDetail === id ? null : id; fillExtra(); return true;
      case "camTile": store.set("camTile", id); syncLiveTiles(); openPageKeep(); return true;
      case "dtAction": if (S.dt) { S.dt.action = id; renderSheet(); renderInfo(); } return true;
      case "dtTemp": if (S.dt) { const e = S.ha.states[S.dt.id]; const step = num(e.attributes, "target_temp_step") || 0.5; S.dt.temp = numFrom(el, S.dt.temp ?? 25, step, num(e.attributes, "min_temp") || 16, num(e.attributes, "max_temp") || 32); renderSheet(); renderInfo(); } return true;
      case "dtAfter": addDevTimer({ after_min: Number(id) }); return true;
      case "dtAt": {
        const v = ($("#dtTime") || {}).value;
        if (!v) { S.dtMsg = "時刻を入れてください"; renderSheet(); renderInfo(); return true; }
        const [hh, mm] = v.split(":").map(Number);
        const t = new Date(); t.setHours(hh, mm, 0, 0); if (t.getTime() < Date.now()) t.setDate(t.getDate() + 1);
        addDevTimer({ at: t.getTime() / 1000 });
        return true;
      }
      case "dtCancel": try { const j = await solarApi("/timers/delete", { id }); S.devTimers = j.timers || []; } catch (e) { /* 次の読み込みで */ } renderEntities(); renderSheet(); renderInfo(); { const l = $("#devTimerList"); if (l) l.innerHTML = devTimerListHTML(); } return true;
      case "nightPct": loadSolar("/config", { night_heat_pct: Number(id) }); return true;
      case "hotAuto": loadSolar("/config", { hot_auto: el.checked }); return true;
      case "heatGuard": loadSolar("/config", { heat_guard: el.checked }); return true;
      case "ssBool": loadSolar("/config", { [id]: el.checked }); return true;
      case "raToggle": {
        const cur = (S.solar && S.solar.config && S.solar.config.run_alerts) || [];
        const e = S.ha && S.ha.states[id];
        loadSolar("/config", { run_alerts: el.checked ? [...cur.filter((x) => x.entity !== id), { entity: id, name: entName(id), hours: e && dom(e) === "climate" ? 4 : 2 }] : cur.filter((x) => x.entity !== id) });
        return true;
      }
      case "raHours": {
        const cur = (S.solar && S.solar.config && S.solar.config.run_alerts) || [];
        loadSolar("/config", { run_alerts: cur.map((x) => (x.entity === id ? Object.assign({}, x, { hours: Number(el.dataset.v) }) : x)) });
        return true;
      }
      case "ssNum": loadSolar("/config", { [id]: Number(el.dataset.v) }); return true;
      case "ssAc": {
        const cur = (S.solar && S.solar.config && S.solar.config.ss_aircon_entities) || [];
        loadSolar("/config", { ss_aircon_entities: el.checked ? [...new Set([...cur, id])] : cur.filter((x) => x !== id) });
        return true;
      }
      case "ssLoad": {
        const cur = (S.solar && S.solar.config && S.solar.config.ss_loads_items) || [];
        const e = S.ha && S.ha.states[id];
        loadSolar("/config", { ss_loads_items: el.checked ? [...cur.filter((x) => x.entity !== id), { entity: id, name: e ? nameOf(e) : id, on_kw: 1.5, off_kw: 0.3 }] : cur.filter((x) => x.entity !== id) });
        return true;
      }
      case "ssLoadKw": {
        const cur = (S.solar && S.solar.config && S.solar.config.ss_loads_items) || [];
        loadSolar("/config", { ss_loads_items: cur.map((x) => (x.entity === id ? Object.assign({}, x, { [el.dataset.f]: Number(el.dataset.v) }) : x)) });
        return true;
      }
      case "nightLate": loadSolar("/config", { night_late: el.checked }); return true;
      case "hotTempLearn": loadSolar("/config", { hot_temp_learn: el.checked }); return true;
      case "hotMargin": loadSolar("/config", { hot_margin_l: Number(id) }); return true;
      case "hotMarginIn": { const m = (S.solar && S.solar.config && S.solar.config.hot_margin_l) ?? 50; loadSolar("/config", { hot_margin_l: Math.round(numFrom(el, m, 10, 0, 500)) }); return true; }
      case "prepHour": loadSolar("/config", { prep_hour: Number(id) }); return true;
      case "hotExtraOpen": S.hotExtraOpen = true; renderSolar(); return true;
      case "hotExtraClose": S.hotExtraOpen = false; renderSolar(); return true;
      case "hotExtraDel": {
        const ex = ((S.solar && S.solar.hot_plan && S.solar.hot_plan.extra) || []).filter((_, i) => i !== Number(id));
        await saveHotExtra(ex); return true;
      }
      case "hotExtraSave": {
        const d = ($("#hxDate") || {}).value;
        if (!d) return true;
        const ex = ((S.solar && S.solar.hot_plan && S.solar.hot_plan.extra) || []).concat([{ date: d, part: $("#hxPart").value, liters: Number($("#hxLiters").value), note: $("#hxNote").value.trim() }]);
        S.hotExtraOpen = false;
        await saveHotExtra(ex); return true;
      }
      case "groupRun": runGroup(id, el.dataset.act); return true;
      case "awayOpen": S.awayOpen = true; renderSolar(); return true;
      case "awayClose": S.awayOpen = false; renderSolar(); return true;
      case "awayCancel": S.awayOpen = false; await loadSolar("/away", { cancel: true }); loadLive(); return true;
      case "awaySave": {
        const v = ($("#awayAt") || {}).value;
        const t = v ? new Date(v).getTime() : 0;
        if (!t || t < Date.now()) { S.solarErr = "帰る日時が過ぎています"; renderSolar(); return true; }
        S.awayOpen = false;
        let err = null;
        try {
          const r = await solarApi("/away", { until: t / 1000, reheat_hours: Number(($("#awayReheat") || {}).value || 6) });
          err = r.error || null;
        } catch (e) { err = "サーバーに送れませんでした：" + e.message; }
        await loadSolar(); loadLive();
        if (err) { S.solarErr = err; renderSolar(); }
        return true;
      }
      case "camRecToggle": {
        const cams = ((S.camRec && S.camRec.cameras) || []).map((c) => ({ id: c.id, name: c.name, kind: c.kind, entity: c.entity })).filter((c) => c.id !== id);
        if (el.checked) cams.push({ id, name: el.dataset.name, kind: "ha", entity: el.dataset.entity });
        try { S.camRec = await camApi("/config", { cameras: cams }); S.camRecErr = null; } catch (e) { S.camRecErr = "サーバーに送れませんでした：" + e.message; }
        fillExtra(); return true;
      }
      case "motionOpen": if (S.motion && S.motion.cam === id) { S.motion = null; fillExtra(); } else loadMotion(id); return true;
      case "motionPick": if (S.motion) { S.motion.sel = Number(id); fillExtra(); loadMotionFrame(S.motion); } return true;
      case "opHours": S.oplog = Object.assign(S.oplog || {}, { hours: Number(id) }); loadOpLog(); return true;
      case "opKind": S.oplog = Object.assign(S.oplog || {}, { kind: id }); fillExtra(); return true;
      case "helpGo": { const t = document.getElementById("help-" + id); if (t) t.scrollIntoView({ behavior: "smooth", block: "start" }); return true; }
      case "helpTop": { const p = $("#page"); if (p) p.scrollTo({ top: 0, behavior: "smooth" }); return true; }
      default: return false;
    }
  }

  // ================= カメラ =================
  const camModes = () => store.get("camModes", {});
  const CAM_MODES = [["auto", "自動", "いちばん遅れの少ない方式"], ["realtime", "リアルタイム", "遅れ1秒未満（WebRTC）"], ["hls", "高画質", "なめらか・数秒の遅れ（HLS）"], ["mjpeg", "簡易", "どのカメラでも・コマ数少なめ"]];
  const camSnapUrls = new Map();
  async function refreshSnapshot(id) {
    const e = S.ha && S.ha.states[id];
    if (!e || isUnav(e)) return;
    try {
      const token = await accessToken();
      const r = await fetch(`/api/camera_proxy/${id}?t=${Date.now()}`, { headers: { Authorization: "Bearer " + token } });
      if (!r.ok) return;
      const url = URL.createObjectURL(await r.blob());
      const old = camSnapUrls.get(id);
      camSnapUrls.set(id, url);
      document.querySelectorAll(`img[data-snap="${CSS.escape(id)}"]`).forEach((img) => { img.src = url; });
      if (old) setTimeout(() => URL.revokeObjectURL(old), 2000);
    } catch (e) { /* 次の更新で */ }
  }
  function refreshSnapshots() {
    syncLiveTiles();
    if (S.ssOn || S.cam || document.visibilityState !== "visible") return;
    document.querySelectorAll("img[data-snap]").forEach((img) => refreshSnapshot(img.dataset.snap));
  }
  // ---- 機器のタイマー（サーバーが Home Assistant を通して動かす） ----
  const TIMER_DOMAINS = ["switch", "light", "fan", "input_boolean", "climate", "media_player"];
  const timerCapable = (e) => TIMER_DOMAINS.includes(dom(e));
  async function loadDeviceTimers() {
    if (!SERVER_MODE) return;
    try {
      const j = await solarApi("/timers");
      S.devTimers = j.timers || [];
      if (j.error) S.dtMsg = j.error;
    } catch (e) { /* サーバーなし */ }
    renderEntities(); renderSheet(); renderInfo();
    const l = $("#devTimerList"); if (l) l.innerHTML = devTimerListHTML();
  }
  const dtWhen = (sec) => {
    const d = new Date(sec * 1000), t = new Date();
    const day = d.toDateString() === t.toDateString() ? "" : new Date(t.getTime() + 86400000).toDateString() === d.toDateString() ? "明日 " : `${d.getMonth() + 1}/${d.getDate()} `;
    return `${day}${d.getHours()}:${pad(d.getMinutes())}`;
  };
  const dtRemain = (sec) => { const m = Math.max(0, Math.round((sec * 1000 - Date.now()) / 60000)); return m >= 60 ? `${Math.floor(m / 60)}時間${m % 60 ? (m % 60) + "分" : ""}` : `${m}分`; };
  function timerBadgeHTML(e) {
    const t = (S.devTimers || []).filter((x) => x.entity === e.entity_id).sort((a, b) => a.at - b.at)[0];
    return t ? `<div class="dtbadge">⏲ ${dtWhen(t.at)} に${esc(t.label)}</div>` : "";
  }
  function devTimerSectionHTML(e) {
    if (!SERVER_MODE || !timerCapable(e)) return "";
    const st = (S.dt && S.dt.id === e.entity_id) ? S.dt : (S.dt = { id: e.entity_id, action: dom(e) === "climate" ? (e.state === "off" ? ((e.attributes.hvac_modes || []).find((m) => m !== "off") || "off") : "off") : (isOn(e) ? "turn_off" : "turn_on"), temp: 25 });
    const acts = dom(e) === "climate" ? [["off", "停止"], ...(e.attributes.hvac_modes || []).filter((m) => m !== "off").map((m) => [m, hvacLabel(m)])] : [["turn_off", "切る"], ["turn_on", "つける"]];
    const mine = (S.devTimers || []).filter((x) => x.entity === e.entity_id).sort((a, b) => a.at - b.at);
    return `<div class="sec">⏲ タイマー</div>
      ${mine.map((t) => `<div class="kv"><span>${dtWhen(t.at)} に${esc(t.label)}（あと${dtRemain(t.at)}）</span><button class="tbtn" data-set="dtCancel" data-id="${esc(t.id)}">取り消す</button></div>`).join("")}
      <div class="chips">${acts.map(([k, l]) => `<button class="chip${st.action === k ? " sel" : ""}" data-set="dtAction" data-id="${esc(k)}">${esc(l)}</button>`).join("")}</div>
      ${dom(e) === "climate" && st.action !== "off" ? `<div class="brow" style="align-items:center"><span class="note">設定温度</span>${numInputHTML("dtTemp", st.temp ?? 25, "℃", "")}</div>` : ""}
      <div class="brow">${[30, 60, 90, 120, 180, 240].map((m) => `<button class="tb" data-set="dtAfter" data-id="${m}">${m >= 60 ? (m / 60) + "時間後" : m + "分後"}</button>`).join("")}</div>
      <div class="brow" style="align-items:center"><input type="time" id="dtTime" class="search" style="max-width:150px;margin:0"><button class="tbtn" data-set="dtAt">この時刻に</button></div>
      ${S.dtMsg ? `<div class="warn">${esc(S.dtMsg)}</div>` : ""}
      <div class="note">ホームパネルのサーバーが動かすので、iPad を閉じていても動きます。</div>`;
  }
  function devTimerListHTML() {
    const list = (S.devTimers || []).slice().sort((a, b) => a.at - b.at);
    return list.length ? list.map((t) => `<div class="kv"><span>${dtWhen(t.at)}　${esc(t.name)} を${esc(t.label)}（あと${dtRemain(t.at)}）</span><button class="tbtn" data-set="dtCancel" data-id="${esc(t.id)}">取消</button></div>`).join("") : `<div class="note">ありません（機器の詳細の画面から設定できます）</div>`;
  }
  async function addDevTimer(body) {
    const e = S.ha && S.ha.states[S.dt.id];
    if (!e) return;
    S.dtMsg = null;
    const b = Object.assign({ entity: e.entity_id, name: nameOf(e), by: S.user ? S.user.name : "iPad" }, body);
    if (dom(e) === "climate") { b.action = "set_hvac_mode"; b.data = { hvac_mode: S.dt.action }; if (S.dt.action !== "off" && S.dt.temp != null) b.data.temperature = S.dt.temp; }
    else b.action = S.dt.action;
    try { const j = await solarApi("/timers", b); if (j.error) S.dtMsg = j.error; S.devTimers = j.timers || S.devTimers; } catch (err) { S.dtMsg = "タイマーを設定できませんでした：" + err.message; }
    renderEntities(); renderSheet(); renderInfo();
  }

  // ---- ホーム画面を上の端で下に引っぱると、読み直す ----
  async function refreshAll() {
    const ind = $("#ptr");
    if (ind) { ind.classList.add("busy"); ind.textContent = "⟳ 読み直しています…"; }
    try {
      if (S.ha) {
        const arr = await S.ha.send({ type: "get_states" });
        arr.forEach((x) => { S.ha.states[x.entity_id] = x; });
        renderEntities(); renderThermos(); renderWeather();
      }
      loadEnergy(); loadEvents(); loadSolar(); loadClimate(); loadDeviceTimers(); refreshSnapshots();
    } catch (e) { /* つながっていなければ接続のやり直しに任せる */ }
    setTimeout(() => { if (ind) { ind.classList.remove("busy", "show"); ind.style.height = "0px"; } }, 600);
  }
  function setupPullToRefresh() {
    let y0 = null, pulled = 0;
    const ind = $("#ptr");
    const atTop = () => (document.scrollingElement || document.documentElement).scrollTop <= 0;
    const blocked = () => S.page || S.cam || S.detail || S.ssOn;
    document.addEventListener("touchstart", (ev) => { y0 = !blocked() && atTop() && ev.touches.length === 1 ? ev.touches[0].clientY : null; pulled = 0; }, { passive: true });
    document.addEventListener("touchmove", (ev) => {
      if (y0 === null) return;
      pulled = ev.touches[0].clientY - y0;
      if (pulled <= 0 || !atTop()) { ind.style.height = "0px"; return; }
      const h = Math.min(70, pulled / 2);
      ind.style.height = h + "px";
      ind.classList.add("show");
      ind.textContent = pulled > 120 ? "↻ 指を離すと読み直します" : "↓ 引っぱって読み直す";
    }, { passive: true });
    document.addEventListener("touchend", () => {
      if (y0 === null) return;
      y0 = null;
      if (pulled > 120) refreshAll();
      else { ind.style.height = "0px"; ind.classList.remove("show"); }
    });
  }

  // ---- ホーム画面のタイルにそのまま動画（MJPEG）を映す ----
  const camTileLive = () => store.get("camTile", "live") === "live";
  const liveRetryAt = new Map();
  function syncLiveTiles() {
    const stop = !camTileLive() || S.ssOn || S.cam || document.visibilityState !== "visible";
    document.querySelectorAll(".camimg").forEach((box) => {
      const id = box.dataset.id;
      let v = box.querySelector("img.camlive");
      const e = S.ha && S.ha.states[id];
      if (stop || !e || isUnav(e)) {
        if (v) { v.onerror = null; v.removeAttribute("src"); v.remove(); }
        box.classList.remove("live");
        return;
      }
      if (v || Date.now() < (liveRetryAt.get(id) || 0)) return;
      v = document.createElement("img");
      v.className = "camlive";
      v.alt = "";
      v.onerror = () => { v.onerror = null; v.removeAttribute("src"); v.remove(); box.classList.remove("live"); liveRetryAt.set(id, Date.now() + 60000); };
      v.src = `/api/camera_proxy_stream/${id}?token=${encodeURIComponent(e.attributes.access_token || "")}`;
      box.insertBefore(v, box.querySelector(".camhint"));
      box.classList.add("live");
    });
  }
  document.addEventListener("visibilitychange", () => syncLiveTiles());
  function cameraTileHTML(e) {
    const url = camSnapUrls.get(e.entity_id);
    return `<div class="tile wide cam${isUnav(e) ? " unav" : ""}" data-entity="${esc(e.entity_id)}">${headHTML(e, false)}
      <div class="camimg" data-set="camOpen" data-id="${esc(e.entity_id)}"><img data-snap="${esc(e.entity_id)}" alt="" ${url ? `src="${url}"` : ""}>
      <span class="camhint">${isUnav(e) ? "カメラにつながりません" : `<span class="lv">● ライブ　タップで全画面</span><span class="nv">▶ タップで動画</span>`}</span></div></div>`;
  }
  let camPc = null, camSub = null;
  function closeCameraStream() {
    if (camPc) { try { camPc.close(); } catch (e) { /* noop */ } camPc = null; }
    if (camSub !== null && S.ha) { S.ha.unsub(camSub); S.ha.send({ type: "unsubscribe_events", subscription: camSub }).catch(() => {}); }
    camSub = null;
    const v = $("#camVideo");
    if (v) { v.pause(); v.removeAttribute("src"); v.srcObject = null; v.load(); }
  }
  function closeCamera() { closeCameraStream(); S.cam = null; camZoomReset(); renderCameraBox(); syncLiveTiles(); }
  async function webrtcSupported(id) {
    try {
      const r = await S.ha.send({ type: "camera/capabilities", entity_id: id });
      return (r.frontend_stream_types || []).includes("web_rtc");
    } catch (e) { const st = S.ha.states[id]; return !!(st && st.attributes.frontend_stream_type === "web_rtc"); }
  }
  async function startWebRtc(id, video) {
    let ice = [];
    try { const c = await S.ha.send({ type: "camera/webrtc/get_client_config", entity_id: id }); ice = (c.configuration && c.configuration.iceServers) || []; } catch (e) { /* 家の中なら無くてもつながる */ }
    const pc = (camPc = new RTCPeerConnection({ iceServers: ice }));
    pc.addTransceiver("video", { direction: "recvonly" });
    pc.addTransceiver("audio", { direction: "recvonly" });
    const stream = new MediaStream();
    pc.ontrack = (ev) => { stream.addTrack(ev.track); video.srcObject = stream; video.play().catch(() => {}); };
    let session = null;
    const queued = [];
    const sendCand = (c) => S.ha.send({ type: "camera/webrtc/candidate", entity_id: id, session_id: session, candidate: { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex } }).catch(() => {});
    pc.onicecandidate = (ev) => { if (!ev.candidate) return; if (session) sendCand(ev.candidate); else queued.push(ev.candidate); };
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("つながるまでに時間がかかっています")), 12000);
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === "connected") { clearTimeout(timer); resolve(); }
        if (pc.connectionState === "failed") { clearTimeout(timer); reject(new Error("映像の通信がつながりませんでした")); }
      };
      S.ha.subscribe({ type: "camera/webrtc/offer", entity_id: id, offer: offer.sdp }, (ev) => {
        if (ev.type === "session") { session = ev.session_id; queued.splice(0).forEach(sendCand); }
        else if (ev.type === "answer") pc.setRemoteDescription({ type: "answer", sdp: ev.answer }).catch((e) => reject(e));
        else if (ev.type === "candidate") { const c = typeof ev.candidate === "string" ? { candidate: ev.candidate, sdpMLineIndex: 0 } : ev.candidate; pc.addIceCandidate(c).catch(() => {}); }
        else if (ev.type === "error") { clearTimeout(timer); reject(new Error(ev.message || ev.code)); }
      }).then((sid) => { camSub = sid; }).catch((e) => { clearTimeout(timer); reject(e); });
    });
  }
  async function playCamera() {
    const c = S.cam;
    if (!c) return;
    closeCameraStream();
    const id = c.id, mode = camModes()[id] || "auto";
    const order = mode === "realtime" ? ["webrtc", "hls", "mjpeg"] : mode === "hls" ? ["hls", "mjpeg"] : mode === "mjpeg" ? ["mjpeg"] : ["webrtc", "hls", "mjpeg"];
    for (const p of order.filter((x) => !c.failed.includes(x))) {
      if (!S.cam || S.cam.id !== id) return;
      c.playing = p; c.status = "映像を読み込んでいます…"; renderCameraBox();
      const video = $("#camVideo"), img = $("#camImg");
      try {
        if (p === "webrtc") {
          if (!(await webrtcSupported(id))) continue;
          await startWebRtc(id, video);
        } else if (p === "hls") {
          const e = S.ha.states[id];
          if (!e || ((e.attributes.supported_features || 0) & 2) === 0) continue;
          const r = await S.ha.send({ type: "camera/stream", entity_id: id, format: "hls" });
          if (!video.canPlayType("application/vnd.apple.mpegurl")) throw new Error("このブラウザは HLS を再生できません");
          video.src = r.url;
          await video.play();
        } else {
          const e = S.ha.states[id];
          img.src = `/api/camera_proxy_stream/${id}?token=${encodeURIComponent((e && e.attributes.access_token) || "")}`;
        }
        if (S.cam && S.cam.id === id) { c.status = null; renderCameraBox(); }
        return;
      } catch (e) {
        closeCameraStream();
        c.failed.push(p);
        c.note = `${{ webrtc: "リアルタイム", hls: "高画質", mjpeg: "簡易" }[p]}で再生できないため、ほかの方式に切り替えました（${e.message}）`;
      }
    }
  }
  function openCamera(id) { S.cam = { id, failed: [], playing: null, status: null, note: null, menu: false }; camZoomReset(); syncLiveTiles(); renderCameraBox(); playCamera(); }

  // ---- 全画面のカメラの拡大：2本指で広げる・縮める、拡大中は1本指で動かす、ダブルタップで拡大／元に戻す ----
  const CZ = { s: 1, x: 0, y: 0 };
  function camZoomApply() {
    const box = $("#camBox");
    if (!box) return;
    const w = box.clientWidth, h = box.clientHeight;
    const mx = (CZ.s - 1) * w / 2, my = (CZ.s - 1) * h / 2;
    CZ.x = Math.max(-mx, Math.min(mx, CZ.x)); CZ.y = Math.max(-my, Math.min(my, CZ.y));
    const t = CZ.s > 1.01 ? `translate(${CZ.x}px, ${CZ.y}px) scale(${CZ.s})` : "";
    ["#camVideo", "#camImg"].forEach((q) => { const el = $(q); if (el) el.style.transform = t; });
    const b = $("#camZoom");
    if (b) { b.classList.toggle("hidden", CZ.s <= 1.01); b.textContent = `🔍 ${CZ.s.toFixed(1)}倍　元に戻す`; }
  }
  function camZoomReset() { CZ.s = 1; CZ.x = 0; CZ.y = 0; camZoomApply(); }
  /** p（画面の点）を動かさずに、倍率を ns にする */
  function camZoomAt(ns, px, py) {
    const box = $("#camBox"), r = box.getBoundingClientRect();
    ns = Math.max(1, Math.min(6, ns));
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2, z = ns / CZ.s;
    CZ.x = (px - cx) * (1 - z) + CZ.x * z; CZ.y = (py - cy) * (1 - z) + CZ.y * z; CZ.s = ns;
    camZoomApply();
  }
  function setupCamZoom() {
    const box = $("#camBox");
    if (!box) return;
    let pinch = null, pan = null, lastTap = 0, touchAt = 0;
    const onUi = (ev) => ev.target.closest(".cambar, .cammodes, .camzoom, .camnote");
    const dist = (a, b) => Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    box.addEventListener("touchstart", (ev) => {
      touchAt = Date.now();
      if (onUi(ev)) return;
      if (ev.touches.length === 2) {
        const [a, b] = ev.touches;
        pinch = { d: dist(a, b), s: CZ.s }; pan = null;
      } else if (ev.touches.length === 1) {
        const t = ev.touches[0], now = Date.now();
        if (now - lastTap < 300) { // ダブルタップ
          if (CZ.s > 1.05) camZoomReset(); else camZoomAt(2.5, t.clientX, t.clientY);
          lastTap = 0; ev.preventDefault(); return;
        }
        lastTap = now;
        pan = CZ.s > 1.01 ? { x: t.clientX, y: t.clientY } : null;
      }
    }, { passive: false });
    box.addEventListener("touchmove", (ev) => {
      if (onUi(ev)) return;
      if (pinch && ev.touches.length === 2) {
        const [a, b] = ev.touches;
        camZoomAt(pinch.s * dist(a, b) / pinch.d, (a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2);
        pinch.s = CZ.s; pinch.d = dist(a, b);
        ev.preventDefault();
      } else if (pan && ev.touches.length === 1) {
        const t = ev.touches[0];
        CZ.x += t.clientX - pan.x; CZ.y += t.clientY - pan.y; pan = { x: t.clientX, y: t.clientY };
        camZoomApply(); ev.preventDefault();
      }
    }, { passive: false });
    box.addEventListener("touchend", (ev) => { if (ev.touches.length < 2) pinch = null; if (!ev.touches.length) pan = null; });
    // iOS の Safari が自分で拡大するのを止める（映像だけを拡大する）
    box.addEventListener("gesturestart", (ev) => ev.preventDefault());
    // マウス・トラックパッド：ダブルクリックとホイール
    box.addEventListener("dblclick", (ev) => { if (onUi(ev) || Date.now() - touchAt < 800) return; if (CZ.s > 1.05) camZoomReset(); else camZoomAt(2.5, ev.clientX, ev.clientY); });
    box.addEventListener("wheel", (ev) => { if (onUi(ev)) return; ev.preventDefault(); camZoomAt(CZ.s * (ev.deltaY < 0 ? 1.15 : 1 / 1.15), ev.clientX, ev.clientY); }, { passive: false });
    $("#camZoom").onclick = camZoomReset;
    window.addEventListener("resize", camZoomApply);
  }
  function renderCameraBox() {
    const box = $("#camBox");
    if (!box) return;
    const c = S.cam;
    box.classList.toggle("hidden", !c);
    if (!c) return;
    const e = S.ha && S.ha.states[c.id];
    const mode = camModes()[c.id] || "auto";
    $("#camName").textContent = "📷 " + (e ? nameOf(e) : c.id);
    $("#camMode").textContent = "画質：" + ({ webrtc: "リアルタイム", hls: "高画質", mjpeg: "簡易" }[c.playing] || "…") + " ▾";
    $("#camModes").classList.toggle("hidden", !c.menu);
    $("#camModes").innerHTML = CAM_MODES.map(([k, l, s]) => `<button class="chip${mode === k ? " sel" : ""}" data-set="camModeSel" data-id="${k}">${l}<small style="display:block;opacity:.8">${s}</small></button>`).join("");
    $("#camVideo").classList.toggle("hidden", c.playing === "mjpeg");
    $("#camSound").classList.toggle("hidden", c.playing === "mjpeg");
    $("#camImg").classList.toggle("hidden", c.playing !== "mjpeg");
    $("#camNote").textContent = c.status || c.note || "";
    $("#camNote").classList.toggle("hidden", !(c.status || c.note));
  }

  // ================= 自宅の位置 =================
  const LOCATION_WORDS = /近く|近所|周辺|周り|この辺|このあたり|付近|最寄|徒歩|車で|ここから|家から|うちから|距離|何分|行き方|道順|営業時間|何時まで|何時から|休み|定休|店|スーパー|コンビニ|ドラッグ|薬局|病院|クリニック|歯医者|レストラン|ランチ|カフェ|ラーメン|居酒屋|駅|バス|電車|ガソリン|銀行|郵便局|役所|公園|イベント|祭り|映画館/;
  function withHomeLocation(text) {
    const loc = store.get("homeLocation", "");
    if (!loc || !LOCATION_WORDS.test(text)) return text;
    return `${text}（参考：自宅の場所は「${loc}」です。「近く」「この辺」などはここを基準に答えてください）`;
  }
  async function homeFromHa() {
    const r = await S.ha.send({ type: "get_config" });
    if (typeof r.latitude !== "number") throw new Error("Home Assistant に自宅の位置が登録されていません");
    const name = r.location_name && r.location_name !== "Home" ? r.location_name : null;
    return [name, `緯度${r.latitude.toFixed(5)}・経度${r.longitude.toFixed(5)}`].filter(Boolean).join("、");
  }

  // ================= 設定の書き出し・読み込み =================
  // ログインの情報（tokens / admin_tokens）は書き出さない
  const SECRET_KEYS = ["tokens", "admin_tokens", "admin_user", "state", "flash", "openPage"];
  function exportSettings() {
    const settings = {};
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (!k.startsWith("hp_") || SECRET_KEYS.includes(k.slice(3))) continue;
      try { settings[k.slice(3)] = JSON.parse(localStorage.getItem(k)); } catch (e) { /* noop */ }
    }
    const blob = new Blob([JSON.stringify({ app: "homepanel-web", format: 1, exportedAt: new Date().toISOString(), settings }, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    const d = new Date();
    a.href = URL.createObjectURL(blob);
    a.download = `homepanel-settings-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}.json`;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }
  function importSettings(file) {
    const rd = new FileReader();
    rd.onload = () => {
      try {
        const j = JSON.parse(rd.result);
        if (j.app !== "homepanel-web") throw new Error("ホームパネル（iPad 版）の設定ファイルではありません");
        if (!confirm("今の設定を、このファイルの内容に置き換えますか？（ログインはそのままです）")) return;
        for (let i = localStorage.length - 1; i >= 0; i--) { const k = localStorage.key(i); if (k.startsWith("hp_") && !SECRET_KEYS.includes(k.slice(3))) localStorage.removeItem(k); }
        Object.entries(j.settings || {}).forEach(([k, v]) => { if (!SECRET_KEYS.includes(k)) store.set(k, v); });
        store.set("helpShown", true);
        location.reload();
      } catch (e) { alert("読み込めませんでした：" + e.message); }
    };
    rd.readAsText(file);
  }

  // ================= 音声の回答（ポップアップ） =================
  let popupTimer = null;
  function renderPopup() {
    const box = $("#vpop");
    if (!box) return;
    const v = S.voice;
    const show = ["proc", "speak", "done", "fail"].includes(v.type) && S.popupHidden !== v;
    box.classList.toggle("hidden", !show);
    if (!show) return;
    let h = "";
    if (v.type === "proc") h = `<div class="note">「${esc(v.heard || "")}」</div><div>考えています…${v.queued ? "（終わったら続けて聞きます）" : ""}</div>`;
    else if (v.type === "fail") h = `<div class="warn">${esc(v.msg || "")}</div>`;
    else h = `<div class="note">「${esc(v.heard || "")}」${v.type === "speak" ? " 🔊" : ""}</div><div class="vtext">${esc(v.reply || "完了しました")}</div>`;
    $("#vpopBody").innerHTML = h;
    clearTimeout(popupTimer);
    if (v.type === "done") popupTimer = setTimeout(() => { S.popupHidden = v; renderPopup(); }, Math.min(30000, 8000 + (v.reply || "").length * 120));
    if (v.type === "fail") popupTimer = setTimeout(() => { S.popupHidden = v; renderPopup(); }, 7000);
  }

  // ================= 設定のページ（追加分） =================
  function settingsPageHTML(p) {
    switch (p) {
      case "display": {
        const ws = S.ha ? Object.values(S.ha.states).filter((e) => e.entity_id.startsWith("weather.")) : [];
        const w = weatherEntity();
        return `<div class="sec">かんたん表示</div>
          <label class="row"><span class="rt">大きなボタンだけのホーム画面にする<div class="sub">照明・エアコン・スイッチなどを大きなボタンで出し、タップで入／切します。家族や初めての人向け。</div></span>
            <input type="checkbox" data-set="simpleMode" ${store.get("simpleMode", false) ? "checked" : ""}></label>
          <div class="sec">表示のユーザー（家族ごとの表示）</div>${profilesHTML()}
          <div class="sec">配色</div>${themesHTML()}
          ${ws.length > 1 ? `<div class="sec">天気</div>` + ws.map((x) => `<label class="row"><input type="radio" name="wx" data-set="weather" data-id="${esc(x.entity_id)}" ${w && x.entity_id === w.entity_id ? "checked" : ""}>
            <span class="rt">${esc(nameOf(x))}<div class="sub">${esc(x.entity_id)}</div></span></label>`).join("") : ""}`;
      }
      case "rooms": {
        const rs = rooms();
        return `<div class="sec">部屋</div>
          <label class="row"><span class="rt">部屋ごとに分けて表示する<div class="sub">Home Assistant のエリアを部屋として使います。アプリで部屋を追加したり、機器の部屋を変えたりもできます（下の「表示する機器」で機器ごとに選べます）。</div></span>
            <input type="checkbox" data-set="groupRooms" ${S.groupRooms ? "checked" : ""}></label>
          <div class="note">▲▼ で、ホーム画面に並べる部屋の順番を変えられます。</div>
          ${rs.map((r, i) => `<div class="row editrow" style="cursor:default"><span class="ord"><button class="tbtn" data-set="roomUp" data-id="${esc(r.key)}" ${i === 0 ? "disabled" : ""} aria-label="${esc(r.name)}を上へ">▲</button><button class="tbtn" data-set="roomDown" data-id="${esc(r.key)}" ${i === rs.length - 1 ? "disabled" : ""} aria-label="${esc(r.name)}を下へ">▼</button></span>
            <span class="rt">${esc(r.name)}<div class="sub">${r.ha ? "Home Assistant のエリア" : "アプリで追加した部屋"}</div></span>
            ${r.ha ? "" : `<span class="ops"><button class="tbtn" data-set="roomRename" data-id="${esc(r.key)}">名前を変える</button><button class="tbtn danger" data-set="roomDel" data-id="${esc(r.key)}">削除</button></span>`}</div>`).join("")}
          <div class="brow" style="align-items:center;margin-top:6px"><input class="search" id="newRoom" type="text" placeholder="部屋を追加（例：子ども部屋）" style="max-width:320px;margin:0">
            <button class="tbtn" data-set="roomAdd">追加</button></div>
          <div class="sec" id="selTitle"></div>
          <input class="search" type="search" placeholder="名前で絞り込み" data-set="query">
          <div><button class="tbtn" data-set="all">すべて選択</button><button class="tbtn" data-set="none">すべて解除</button></div>
          <div id="selList"></div>`;
      }
      case "thermo": return `<div class="sec">温度・湿度（SwitchBot などの温度計）</div>${thermoSettingsHTML()}`;
      case "selfsuff": return `<div class="sec">電気の自給率を上げる（オプション）</div><div id="ssBox">${selfSuffHTML()}</div>`;
      case "runalert": return `<div class="sec">つけっぱなしのお知らせ</div><div id="raBox">${runAlertHTML()}</div>`;
      case "voice": {
        const pid = S.pipelineId || S.preferred;
        return `<div class="sec">音声</div>
          <label class="row"><span class="rt">呼びかけで反応する<div class="sub">Home Assistant の音声アシスタントに設定したウェイクワードで反応します。この画面を表示している間だけ待ち受けます。</div></span>
            <input type="checkbox" data-set="wake" ${S.wake ? "checked" : ""}></label>
          ${S.pipelines.length ? `<div class="note" style="margin-top:8px">使うアシスタント（AI の会話エージェントはここで選んだものが答えます）</div>` + S.pipelines.map((p) => `
            <label class="row"><input type="radio" name="pl" data-set="pipeline" data-id="${esc(p.id)}" ${p.id === pid ? "checked" : ""}>
            <span class="rt">${esc(p.name)}<div class="sub">${esc([p.id === S.preferred ? "優先" : null, p.wake ? "呼びかけ対応" : "呼びかけ未設定", p.agent || null].filter(Boolean).join("・"))}</div></span></label>`).join("") : ""}
          ${voiceDiagHTML()}
          <div class="sec">自宅の位置</div>
          <div class="note">「近くのスーパーの営業時間は？」のような場所に関わる質問のときだけ、ここに入れた場所を AI に伝えて、そこを基準に答えさせます。機器の操作には使いません。</div>
          <input type="text" class="search" id="homeLoc" value="${esc(store.get("homeLocation", ""))}" placeholder="例：東京都世田谷区三軒茶屋（町名くらいまでで十分です）">
          <div class="brow"><button class="btn" data-set="homeLocSave">保存</button><button class="btn outline" data-set="homeLocHa">Home Assistant の自宅の位置を使う</button></div>
          <div class="note ok-msg" id="homeLocMsg" role="status" aria-live="polite"></div>`;
      }
      case "screensaver": return `<div class="sec">スクリーンセーバー</div>${screensaverSettingsHTML()}`;
      case "calendar": return `<div class="sec">カレンダー（TimeTree）</div>${calendarSettingsHTML()}`;
      case "cameras": return `<div class="sec">ホーム画面のカメラ</div>
        <div class="chips"><button class="chip${camTileLive() ? " sel" : ""}" data-set="camTile" data-id="live">動画（タップしなくても映る）</button><button class="chip${camTileLive() ? "" : " sel"}" data-set="camTile" data-id="snapshot">静止画（10秒ごとに更新）</button></div>
        <div class="note">${camTileLive() ? "ホーム画面のカメラのタイルに、そのまま動画を映します（音は出しません）。スクリーンセーバー中や全画面で見ている間は止めます。動きが重いときは「静止画」にしてください。" : "ホーム画面には10秒ごとの静止画を出し、タップすると動画になります。"}</div>
        <div class="sec">カメラ</div>
        <div class="note">Home Assistant に登録したカメラを、ホーム画面に静止画（10秒ごとに更新）で表示します。タップすると全画面の動画になり、右上の「画質」で方式を選べます（カメラごとに覚えます）。</div>
        <div class="note">・自動：使える中でいちばん遅れの少ない方式<br>・リアルタイム：遅れ1秒未満（WebRTC。Home Assistant 2024.11 以降）<br>・高画質：なめらか・数秒の遅れ（HLS）<br>・簡易：どのカメラでも見られるがコマ数少なめ</div>
        <div class="note">※ Home Assistant を通さずにネットワークカメラ（RTSP）へ直接つなぐ機能は、ブラウザでは使えないため Android 版だけです。iPad では、カメラを Home Assistant に登録してください。</div>`;
      case "connection": return `<div class="sec">接続</div><div class="note">このアプリのアドレス：${esc(location.origin + location.pathname)}<br>
        ${SERVER_MODE ? "家の中の専用サーバー（Proxmox のコンテナ）から https で配信し、Home Assistant に中継しています。家の中だけで使え、音声入力も使えます。"
          : location.protocol === "https:" ? "家の外からもこのアドレスで使えます。" : "http のアドレスです。音声入力と外出先での利用には https のアドレス（Home Assistant Cloud のリモートURL）で開いてください。"}</div>
        <div class="note">※ ECHONET Lite の機器を Home Assistant を通さずに操作する機能と、おひさま沸き上げ（エコキュート）は、ブラウザでは使えないため Android 版だけです。</div>`;
      case "account": return `<div class="sec">アカウント</div>${accountHTML()}<div class="sec">アカウントの一覧</div><div id="extraBox"></div>`;
      case "climate": return `<div class="sec">エアコンの自動温度調整</div><div id="extraBox"></div>`;
      case "camrec": return `<div class="sec">カメラの記録と動きの分析（2日分）</div><div id="extraBox"></div>`;
      case "oplog": return `<div class="sec">操作の記録</div><div id="extraBox"></div>`;
      case "system": return `<div class="sec">アプリの更新</div><div data-update-slot></div>
        <div class="sec">設定の保存</div>
        <div class="note">表示する機器・部屋・配色・家族ごとの表示などの設定をファイルに書き出し、別の iPad や作り直したときに読み込めます。ログインの情報は含めません。</div>
        <div class="brow"><button class="btn" data-set="exportSettings">設定を書き出す</button><button class="btn outline" data-set="importSettings">設定を読み込む</button></div>
        <input type="file" id="importFile" accept="application/json,.json" class="hidden">`;
    }
    return "";
  }

  async function onNewSetting(el) {
    const id = el.dataset.id;
    switch (el.dataset.set) {
      case "menu": S.menuY = $("#page").scrollTop; S.settingsPage = id; openPage("settings"); return true;
      case "simpleTap": { const e = S.ha && S.ha.states[id]; if (e && !isUnav(e)) primary(e); return true; }
      case "advToggle": S.showAdv = !S.showAdv; openPage("settings"); return true;
      case "simpleMode": store.set("simpleMode", el.checked); applySimple(); return true;
      case "simpleOn": store.set("simpleMode", true); applySimple(); renderGuide(); return true;
      case "simpleOff": store.set("simpleMode", false); applySimple(); return true;
      case "guideOpen": S.settingsPage = id; openPage("settings"); return true;
      case "guideDone": store.set("guideDone", true); renderGuide(); return true;
      case "menuBack": {
        const from = S.settingsPage;
        S.settingsPage = null;
        openPage("settings");
        // 設定のメニューに戻ったら、さっきの位置と項目に戻す（UI-023）
        $("#page").scrollTop = S.menuY || 0;
        const b = $(`#page [data-set="menu"][data-id="${CSS.escape(from || "")}"]`);
        if (b) try { b.focus({ preventScroll: true }); } catch (e) { /* なにもしない */ }
        return true;
      }
      case "profile": switchProfile(id); if (S.page === "settings") openPageKeep(); return true;
      case "profileAdd": {
        const n = prompt("ユーザーの名前（例：パパ、ママ、子ども）");
        if (!n || !n.trim()) return true;
        const p = { id: "p_" + Date.now(), name: n.trim(), sections: activeProfile().sections, theme: S.theme };
        saveProfiles(profiles().concat([p]));
        switchProfile(p.id); openPageKeep();
        return true;
      }
      case "profileRename": { const cur = activeProfile(); const n = prompt("ユーザーの名前", cur.name); if (n && n.trim()) { updateProfile(cur.id, (p) => Object.assign(p, { name: n.trim() })); renderProfileBar(); openPageKeep(); } return true; }
      case "profileDel": {
        const cur = activeProfile();
        if (!confirm(`「${cur.name}」を削除しますか？`)) return true;
        saveProfiles(profiles().filter((p) => p.id !== cur.id));
        switchProfile(profiles()[0].id); openPageKeep();
        return true;
      }
      case "secUp": case "secDown": {
        const i = Number(id), j = el.dataset.set === "secUp" ? i - 1 : i + 1;
        updateProfile(activeProfile().id, (p) => { const s = p.sections; if (j >= 0 && j < s.length) [s[i], s[j]] = [s[j], s[i]]; return p; });
        openPageKeep();
        return true;
      }
      case "secVis": updateProfile(activeProfile().id, (p) => { p.sections[Number(id)].visible = el.checked; return p; }); return true;
      case "quickRun": { const e = S.ha && S.ha.states[id]; if (e) call(e, dom(e) === "automation" ? "trigger" : "turn_on"); return true; }
      case "info": showInfo(id); return true;
      case "infoClose": S.info = null; renderInfo(); return true;
      case "thermoOpen": openThermo(id, 24); return true;
      case "thermoHours": openThermo(S.thermo.key, Number(id)); return true;
      case "thermoClose": S.thermo = null; renderThermoDialog(); return true;
      case "thermoVis": {
        const h = new Set(store.get("thermoHidden", []));
        if (el.checked) h.delete(id); else h.add(id);
        store.set("thermoHidden", [...h]); renderThermos();
        return true;
      }
      case "thermoRename": $("#thermoRenameBox").innerHTML = thermoRenameHTML(id); $("#thermoName").focus(); return true;
      case "thermoRoomName": $("#thermoName").value = id; return true;
      case "thermoNameSave": case "thermoNameReset": {
        const names = store.get("thermoNames", {});
        const v = el.dataset.set === "thermoNameSave" ? $("#thermoName").value.trim() : "";
        if (v) names[id] = v; else delete names[id];
        store.set("thermoNames", names); renderThermos(); openPageKeep();
        return true;
      }
      case "calSel": {
        const all = calendarEntities().map((c) => c.entity_id);
        const sel = new Set(store.get("calendars") || all);
        if (el.checked) sel.add(id); else sel.delete(id);
        store.set("calendars", [...sel]); loadEvents();
        return true;
      }
      case "roomUp": case "roomDown": {
        const keys = rooms().map((r) => r.key);
        const i = keys.indexOf(id), j = el.dataset.set === "roomUp" ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= keys.length) return true;
        [keys[i], keys[j]] = [keys[j], keys[i]];
        store.set("roomOrder", keys); openPageKeep(); renderEntities();
        return true;
      }
      case "homeLocSave": store.set("homeLocation", $("#homeLoc").value.trim()); $("#homeLocMsg").textContent = "保存しました"; return true;
      case "homeLocHa":
        try { const v = await homeFromHa(); $("#homeLoc").value = v; store.set("homeLocation", v); $("#homeLocMsg").textContent = "Home Assistant の自宅の位置を保存しました"; }
        catch (e) { $("#homeLocMsg").textContent = e.message; }
        return true;
      case "camOpen": openCamera(id); return true;
      case "camClose": closeCamera(); return true;
      case "camMenu": if (S.cam) { S.cam.menu = !S.cam.menu; renderCameraBox(); } return true;
      case "camSound": { const v = $("#camVideo"); v.muted = !v.muted; if (!v.muted) v.play().catch(() => {}); el.textContent = (v.muted ? "🔇" : "🔊") + " 音"; return true; }
      case "camModeSel": { const m = camModes(); m[S.cam.id] = id; store.set("camModes", m); S.cam.failed = []; S.cam.note = null; S.cam.menu = false; playCamera(); return true; }
      case "exportSettings": exportSettings(); return true;
      case "importSettings": { const f = $("#importFile"); f.onchange = () => { if (f.files[0]) importSettings(f.files[0]); }; f.click(); return true; }
      case "popClose": S.popupHidden = S.voice; renderPopup(); return true;
      case "solarRetry": loadSolar("/retry", {}); return true;
      case "solarHeat": loadSolar("/heat", { action: id }); return true;
      default: return onServerSetting(el);
    }
  }


  let thermoQueued = false;
  function scheduleThermos() {
    if (thermoQueued) return;
    thermoQueued = true;
    setTimeout(() => { thermoQueued = false; renderThermos(); if (S.thermo) renderThermoDialog(); }, 1000);
  }

  // ================= 画面の組み立て =================
  function setConn(c) {
    S.conn = c;
    const b = $("#badge");
    if (b) {
      // 家の中（🏠）か外出先（🌏）かを、ひと目で分かるアイコンで出す
      const home = SERVER_MODE || /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.|localhost$)/.test(location.hostname) || location.hostname.endsWith(".local");
      const [icon, color, label] = { connected: home ? ["🏠", "#66bb6a", "家の中から接続"] : ["🌏", "#42a5f5", "外出先から接続"], connecting: ["⏳", "#ffca28", "接続しています"], retry: ["⚠", "#ef5350", "再接続待ち"] }[c];
      b.innerHTML = `<span class="conn" style="background:${color}2e;color:${color}"><span class="ci">${icon}</span><span class="cl">${label}</span></span>`;
    }
    renderMsg();
    renderVoice();
    renderEntities();
  }
  function renderMsg() { const m = $("#msg"); if (m) { m.textContent = S.msg || ""; m.classList.toggle("hidden", !S.msg); } }

  function showLogin(reason) {
    $("#app").innerHTML = `<div class="login"><img src="apple-touch-icon.png" alt="">
      <h1 style="margin:0">ホームパネル</h1>
      <div class="note">Home Assistant のログイン画面で、最初の1回だけログインします。<br>ログイン情報はこの端末のブラウザ内に保存されます。</div>
      ${reason ? `<div class="warn">${esc(reason)}</div>` : ""}
      <div><button class="btn" id="loginBtn">Home Assistant でログイン</button></div>
      ${window.isSecureContext ? "" : `<div class="note">※ いまは http のアドレスで開いています。音声入力と外出先での利用には、Home Assistant Cloud のリモートURL（https）で開いてください。</div>`}</div>`;
    $("#loginBtn").onclick = () => startLogin();
  }

  function buildMain() {
    $("#app").innerHTML = `<div class="ptr" id="ptr"></div><div class="wrap">
      <div class="top"><span class="badge" id="badge"></span><span class="profbar" id="profileBar"></span><span class="sp"></span>
        <button class="tbtn hidden" id="btnClimate"></button><button class="tbtn" id="btnHelp" aria-label="使い方"><span class="ti">？</span><span class="tl"> 使い方</span></button><button class="tbtn" id="btnTimers" aria-label="タイマー"><span class="ti">⏱</span><span class="tl"> タイマー</span></button><button class="tbtn" id="btnAuto" aria-label="自動化"><span class="ti">⏰</span><span class="tl"> 自動化</span></button><button class="tbtn" id="btnSettings" aria-label="設定"><span class="ti">⚙</span><span class="tl"> 設定</span></button></div>
      <div id="updateBanner"></div>
      <div class="msg hidden" id="msg" role="status" aria-live="polite"></div>
      <div id="guide"></div>
      <div id="groups"></div>
      <div id="simpleBox" class="hidden"></div>
      <div id="home">
        <div data-sec="clock"><section class="hero"><div><div class="time" id="time"></div><div class="date" id="date"></div></div><div id="weather"></div>
          <div class="vbox"><div class="vtexts"><div class="vstatus" id="vstatus"></div><div class="vnote" id="vnote"></div></div><button class="mic" id="mic">🎤</button></div></section></div>
        <div data-sec="thermo"><div class="thermos" id="thermo"></div></div>
        <div data-sec="calendar"><div id="cal"></div></div>
        <div data-sec="timers"><div class="timerstrip hidden" id="timerStrip"></div></div>
        <div data-sec="energy"><div id="live"></div><div id="energy"></div><div id="solar"></div></div>
        <div data-sec="automations"><div id="quick"></div></div>
        <div data-sec="devices"><h2 class="sechead">🏠 機器の操作</h2><div class="roombar" id="roombar"></div><main class="grid" id="grid"></main>
          <div class="note" id="empty" style="text-align:center;padding:32px"></div></div>
      </div>
    </div>
    <div class="vpop hidden" id="vpop" role="dialog" aria-modal="true" aria-label="音声"><div class="vpop-in"><button class="tbtn vpop-x" data-set="popClose">✕ 閉じる</button><div id="vpopBody"></div></div></div>
    <div class="sheet hidden" id="sheet" role="dialog" aria-modal="true" aria-label="機器の詳細"><div class="sheet-bg" id="sheetBg"></div>
      <div class="sheet-body"><button class="sheet-close" id="sheetClose" aria-label="閉じる">×</button><div id="sheetContent"></div></div></div>
    <div class="dlgwrap hidden" id="info" role="dialog" aria-modal="true" aria-label="動作の詳細"></div>
    <div class="dlgwrap hidden" id="thermoDlg" role="dialog" aria-modal="true" aria-label="温度計"></div>
    <div class="cambox hidden" id="camBox" role="dialog" aria-modal="true" aria-label="カメラ"><video id="camVideo" playsinline autoplay muted></video><img id="camImg" class="hidden" alt="">
      <div class="cambar"><span id="camName" style="flex:1"></span><button class="tbtn" id="camSound" data-set="camSound">🔇 音</button><button class="tbtn" id="camMode" data-set="camMenu"></button><button class="tbtn" data-set="camClose">✕ 閉じる</button></div>
      <div class="chips cammodes hidden" id="camModes"></div><div class="camnote hidden" id="camNote"></div><button class="camzoom hidden" id="camZoom">🔍 元に戻す</button></div>
    <div class="page hidden" id="page"></div>
    <div class="saver hidden" id="saver"><img class="ssimg" alt=""><img class="ssimg" alt="">
      <div class="ssinfo"><div id="ssTime" class="sst"></div><div id="ssDate"></div><div id="ssWeather"></div><div id="ssTimers"></div></div></div>
    <div class="alarm hidden" id="alarm" role="alertdialog" aria-modal="true" aria-label="タイマー"></div>`;

    bindControls($("#grid"));
    bindControls($("#sheetContent"));
    setupCamZoom();
    const closeSheet = () => { S.detail = null; $("#sheetContent").dataset.html = ""; renderSheet(); };
    $("#sheetBg").onclick = closeSheet;
    $("#sheetClose").onclick = closeSheet;
    $("#btnHelp").onclick = () => openPage("help");
    $("#btnClimate").onclick = () => { S.settingsPage = "climate"; openPage("settings"); };
    $("#btnSettings").onclick = () => openPage("settings");
    $("#btnAuto").onclick = () => openPage("auto");
    $("#btnTimers").onclick = () => { unlockAlarm(); openPage("timers"); };
    $("#mic").onclick = onMicPressed;
    renderGuide();
    applySimple();

    // 設定・使い方・更新ボタン（data-set）
    document.addEventListener("click", (ev) => {
      if (ev.target.closest("label.sw, label.row")) return; // チェックボックス類は change で処理
      const el = ev.target.closest("[data-set]");
      if (!el || el.tagName === "INPUT" || el.tagName === "SELECT") return;
      onSetting(el);
    });
    document.addEventListener("change", (ev) => {
      const el = ev.target;
      if (el.dataset && el.dataset.set && (el.tagName === "INPUT" || el.tagName === "SELECT")) onSetting(el);
    });
    document.addEventListener("input", (ev) => {
      const el = ev.target;
      if (!el.dataset) return;
      if (el.dataset.set === "query") renderSelList();
      if (el.dataset.set === "helpQuery") renderHelpList(el.value);
      if (el.dataset.set === "ruleName" && S.edit) S.edit.name = el.value;
      if (el.dataset.set === "actParam" && S.edit) {
        const l = el.parentElement.querySelector(".rl");
        if (l) l.textContent = l.textContent.replace(/\d+%/, el.value + "%");
      }
    });

    // iOS は操作をきっかけにしないと音を出せない・マイクを再開できないので、タップのたびに再開を試みる
    let swallowClick = false;
    document.addEventListener("pointerdown", (ev) => {
      if (S.ssOn) { swallowClick = true; ev.preventDefault(); }
      onUserInteraction();
      unlockAlarm();
      unlockAudio();
      if (mic.ctx && mic.ctx.state === "suspended") mic.ctx.resume().then(kickWake).catch(() => {});
      else kickWake();
    });

    document.addEventListener("click", (ev) => {
      if (swallowClick) { swallowClick = false; ev.stopPropagation(); ev.preventDefault(); }
    }, true);
    document.addEventListener("keydown", onUserInteraction);

    setupPullToRefresh();
    layoutHome();
    renderProfileBar();
    renderClock();
    setInterval(renderClock, 1000);
    setInterval(refreshSnapshots, 10000);
    setInterval(loadEnergy, 10 * 60 * 1000);
    loadSolar();
    setInterval(() => loadSolar(), 30000);
    loadLive();
    setInterval(() => { if (document.visibilityState === "visible") loadLive(); }, 10000);
    loadClimate();
    setInterval(() => loadClimate(), 60000);
    loadDeviceTimers();
    setInterval(() => loadDeviceTimers(), 60000);
    setInterval(loadEvents, 15 * 60 * 1000);
    setInterval(checkTimers, 500);
    setInterval(checkIdle, 2000);
    renderTimers();
    loadPhotos();
    setInterval(loadPhotos, 6 * 60 * 60 * 1000);
    setConn("connecting");
    renderVoice();
    renderUpdate();
    if (!store.get("helpShown", false)) openPage("help");
  }

  // 画面を離れたらマイクを止め、戻ったら再開。画面を消さないようにする
  let wakeLock = null;
  async function keepAwake() {
    try { if ("wakeLock" in navigator && document.visibilityState === "visible") wakeLock = await navigator.wakeLock.request("screen"); } catch (e) { /* 非対応 */ }
  }
  document.addEventListener("visibilitychange", async () => {
    if (document.visibilityState === "visible") { keepAwake(); kickWake(); reloadStatesQuiet(); }
    else { await abortWake(); if (!S.busy) mic.stop(); }
  });

  // ================= 起動 =================
  async function boot() {
    applyTheme();
    try {
      await handleCallback();
    } catch (e) {
      showLogin(e.message);
      return;
    }
    if (!store.get("tokens")) { showLogin(null); return; }
    buildMain();
    const back = store.get("openPage");
    if (back) { store.del("openPage"); openPage(back); }
    const flash = store.get("flash");
    if (flash) { store.del("flash"); setTimeout(() => alert(flash), 300); }
    keepAwake();
    connectLoop();
    setTimeout(() => checkUpdate(true), 10000);
    setInterval(() => checkUpdate(true), 6 * 60 * 60 * 1000);
  }
  boot();
})();
