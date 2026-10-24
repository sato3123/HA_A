/* ホームパネル（iPad / ブラウザ版）
 * Home Assistant の /local/homepanel/ に置いて使う。Home Assistant と同じアドレスから読み込まれるので、
 * ログインは Home Assistant 標準のログイン画面で1回だけ。トークンはこの端末のブラウザ内に保存。
 */
"use strict";

(() => {
  const APP_VERSION = window.HP_VERSION || { version: "dev", code: 0 };
  const REPO = "sato3123/HA_A";
  const UPDATE_SERVICE = { domain: "shell_command", service: "update_homepanel" };
  const SUPPORTED = ["climate", "light", "fan", "cover", "switch", "input_boolean", "scene", "script"];
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
  const isWide = (e) => ({ climate: true, light: dimmable(e), fan: hasPct(e), cover: hasPos(e) }[dom(e)] || false);

  // ================= 表示名 =================
  const DOMAIN_LABEL = { light: "照明", switch: "スイッチ", fan: "ファン", cover: "カーテン・シャッター", climate: "エアコン", input_boolean: "切り替え", scene: "シーン", script: "スクリプト" };
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
      default: return { on: "オン", off: "オフ" }[e.state] || e.state;
    }
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
          scheduleRender();
        });
        setConn("connected");
        S.msg = null;
        backoff = 2000;
        renderEntities();
        renderWeather();
        loadPipelines();
        loadRegistries();
        loadUser().then(() => { if (S.page === "auto") loadRules(); });
        kickWake();
        await ha.closed;
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
    return `<div class="head" data-act="${SIMPLE.includes(dom(e)) ? "primary" : "detail"}">
      <div class="ht"><div class="dom">${esc(DOMAIN_LABEL[dom(e)] || dom(e))}</div>
      <div class="name${large ? " large" : ""}">${esc(nameOf(e))}</div><div class="st">${esc(stateLabel(e))}</div></div>${sw}</div>`;
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
    const d = dom(e), unav = isUnav(e);
    let body = "";
    if (!unav) {
      if (d === "light" && dimmable(e) && e.state === "on") body = brightnessHTML(e);
      else if (d === "fan" && hasPct(e)) body = fanHTML(e);
      else if (d === "cover") body = coverButtons(e) + (hasPos(e) ? positionHTML(e) : "");
      else if (d === "climate") body = tempHTML(e) + chipsHTML(null, e.attributes.hvac_modes, e.state, "set_hvac_mode", "hvac_mode", hvacLabel);
    }
    const cls = ["tile", isWide(e) ? "wide" : "", isOn(e) && d !== "scene" && d !== "script" ? "on" : "", unav ? "unav" : "", SIMPLE.includes(d) ? "simple" : ""].join(" ");
    return `<div class="${cls}" data-entity="${esc(e.entity_id)}">${headHTML(e, false)}${body}</div>`;
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
    return h;
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

  function renderEntities() {
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
        h.textContent = room.name;
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
    if (!same) grid.replaceChildren(...els);
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

  function bindControls(root) {
    root.addEventListener("click", (ev) => {
      if (ev.target.closest("label.sw, input")) return; // スイッチは change で処理
      const el = ev.target.closest("[data-act]");
      if (el) { handleAction(el); return; }
      const tile = ev.target.closest(".tile");
      if (tile) {
        const e = entityFor(tile);
        if (!e || isUnav(e)) return;
        if (SIMPLE.includes(dom(e))) primary(e); else { S.detail = e.entity_id; renderSheet(); }
      }
    });
    root.addEventListener("change", (ev) => {
      const el = ev.target;
      if (el.dataset.act === "range") { commitRange(el); setTimeout(() => { S.dragging = null; scheduleRender(); }, 300); }
      else if (el.dataset.act === "toggle" || el.dataset.act === "osc") handleAction(el);
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
    const now = new Date();
    const t = $("#time"), d = $("#date");
    if (!t) return;
    t.textContent = `${now.getHours()}:${String(now.getMinutes()).padStart(2, "0")}`;
    d.textContent = `${now.getMonth() + 1}月${now.getDate()}日（${DOW[now.getDay()]}）`;
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
  function play(url) {
    return new Promise((res) => {
      player.onended = res;
      player.onerror = res;
      player.src = url;
      player.play().catch(() => res());
    });
  }

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
      if (!this.sink) { this.carry = new Float32Array(0); this.t = 0; return; }
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
      this.sink(pcm);
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

  /** 音声（wake: 呼びかけ待ちから）または文字で Assist パイプラインを実行。命令を処理したら true */
  async function runPipeline({ wake = false, text = null }) {
    const ha = S.ha;
    if (!ha) throw new Error("接続していません");
    const isText = text !== null;
    const input = isText ? { text } : Object.assign({ sample_rate: 16000 }, wake ? { timeout: 600 } : {});
    const msg = { type: "assist_pipeline/run", start_stage: isText ? "intent" : wake ? "wake_word" : "stt", end_stage: "tts", input };
    const pid = effectivePipeline();
    if (pid) msg.pipeline = pid;
    if (S.conv && Date.now() - S.conv.at < 5 * 60 * 1000) msg.conversation_id = S.conv.id;

    let handler = -1, heard = text || "", reply = "", tts = null, handled = isText, ended = false;
    let deadline = wake ? Infinity : Date.now() + 10000;
    let resolveDone, rejectDone;
    const done = new Promise((res, rej) => { resolveDone = res; rejectDone = rej; });
    const stopAudio = () => {
      if (mic.sink === sink) mic.sink = null;
      if (handler >= 0 && !ended) { ended = true; ha.sendBinary(new Uint8Array([handler])); }
    };
    const sink = (pcm) => {
      if (S.stopReq || Date.now() > deadline) { stopAudio(); return; }
      const frame = new Uint8Array(1 + pcm.length * 2);
      frame[0] = handler;
      frame.set(new Uint8Array(pcm.buffer), 1);
      ha.sendBinary(frame);
    };
    S.stopReq = false;
    if (isText) setVoice({ type: "proc", heard: text });

    const subId = await ha.subscribe(msg, (ev) => {
      const d = ev.data || {};
      switch (ev.type) {
        case "run-start":
          if (isText) break;
          handler = d.runner_data ? d.runner_data.stt_binary_handler_id : -1;
          if (handler === undefined || handler === null || handler < 0) { rejectDone(new Error("音声入力を開始できませんでした")); break; }
          setVoice({ type: wake ? "wait" : "listen" });
          mic.sink = sink;
          break;
        case "wake_word-end":
          if (!d.wake_word_output || !d.wake_word_output.wake_word_id) { resolveDone(); break; }
          deadline = Date.now() + 10000;
          mic.beep();
          setVoice({ type: "listen" });
          break;
        case "stt-vad-end": stopAudio(); break;
        case "stt-end":
          stopAudio();
          handled = true;
          heard = (d.stt_output && d.stt_output.text) || "";
          setVoice({ type: "proc", heard });
          break;
        case "intent-end": {
          const o = d.intent_output || {};
          reply = (o.response && o.response.speech && o.response.speech.plain && o.response.speech.plain.speech) || "";
          if (o.conversation_id) S.conv = { id: o.conversation_id, at: Date.now() };
          break;
        }
        case "tts-end": tts = d.tts_output && d.tts_output.url; break;
        case "error": if (d.code === "wake-word-timeout") resolveDone(); else rejectDone(errFor(d)); break;
        case "run-end": resolveDone(); break;
      }
    });
    currentAbort = () => rejectDone(new AbortErr("中止"));
    const timer = setTimeout(() => rejectDone(new Error("時間切れになりました")), wake ? 690000 : 90000);
    try {
      await done;
    } finally {
      clearTimeout(timer);
      currentAbort = null;
      stopAudio();
      ha.unsub(subId);
    }
    if (handled) {
      setVoice({ type: "done", heard, reply });
      if (tts) await play(tts);
    }
    return handled;
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
            await runPipeline({ wake: true });
            await sleep(300);
          } catch (e) {
            if (e instanceof AbortErr || !shouldWake()) break;
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
    if (p) await p.catch(() => {});
  }

  async function onMicPressed() {
    unlockAudio();
    if (S.voice.type === "listen") { S.stopReq = true; return; }
    if (S.busy || !S.ha) return;
    S.busy = true;
    try {
      await abortWake();
      await mic.ensure();
      await runPipeline({ wake: false });
    } catch (e) {
      if (!(e instanceof AbortErr)) setVoice({ type: "fail", msg: e.message });
    } finally {
      S.busy = false;
      if (!S.wake) mic.stop();
      kickWake();
    }
  }

  async function sendText(text) {
    unlockAudio();
    if (!text.trim() || S.busy || !S.ha) return;
    S.busy = true;
    try {
      await abortWake();
      await runPipeline({ text: text.trim() });
    } catch (e) {
      if (!(e instanceof AbortErr)) setVoice({ type: "fail", msg: e.message });
    } finally {
      S.busy = false;
      kickWake();
    }
  }

  function renderVoice() {
    const st = $("#vstatus");
    if (!st) return;
    const v = S.voice;
    const micBtn = $("#mic");
    micBtn.classList.toggle("listen", v.type === "listen");
    micBtn.textContent = v.type === "listen" ? "■" : "🎤";
    micBtn.disabled = S.conn !== "connected" || !window.isSecureContext;
    st.textContent = {
      idle: S.wake ? "呼びかけの準備をしています…" : "マイクを押して話しかけてください\n例：「リビングの電気をつけて」「近くのスーパーの営業時間は？」",
      wait: "呼びかけを待っています（マイクボタンでもすぐに話せます）",
      listen: "聞いています…（話し終わったら■で終了）",
      proc: `「${v.heard || ""}」を処理しています…`,
      done: `「${v.heard || ""}」`,
      fail: v.msg || "",
    }[v.type];
    let reply = "";
    if (v.type === "done") reply = v.reply || "完了しました";
    else if ((v.type === "wait" || v.type === "idle") && S.lastDone) reply = `前回：「${S.lastDone.heard}」→ ${S.lastDone.reply}`;
    $("#vreply").textContent = reply;
    $("#vnote").textContent = window.isSecureContext ? "" : "※ http のアドレスでは音声入力が使えません（文字入力は使えます）。https のアドレスで開いてください。";
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

  async function doUpdate() {
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
      case "updating": return `<div class="update">更新しています…（終わると自動で再読み込みします）</div>`;
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
    const pid = S.pipelineId || S.preferred;
    const ws = S.ha ? Object.values(S.ha.states).filter((e) => e.entity_id.startsWith("weather.")) : [];
    const w = weatherEntity();
    return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="close">← 戻る</button><h2>設定</h2></div>
      <div class="sec">配色</div>${themesHTML()}

      <div class="sec">部屋</div>
      <label class="row"><span class="rt">部屋ごとに分けて表示する<div class="sub">Home Assistant のエリアを部屋として使います。アプリで部屋を追加したり、機器の部屋を変えたりもできます（下の「表示する機器」で機器ごとに選べます）。</div></span>
        <input type="checkbox" data-set="groupRooms" ${S.groupRooms ? "checked" : ""}></label>
      ${rooms().map((r) => `<div class="row" style="cursor:default"><span class="rt">${esc(r.name)}<div class="sub">${r.ha ? "Home Assistant のエリア" : "アプリで追加した部屋"}</div></span>
        ${r.ha ? "" : `<button class="tbtn" data-set="roomRename" data-id="${esc(r.key)}">名前を変える</button><button class="tbtn" data-set="roomDel" data-id="${esc(r.key)}">削除</button>`}</div>`).join("")}
      <div class="brow" style="align-items:center;margin-top:6px"><input class="search" id="newRoom" type="text" placeholder="部屋を追加（例：子ども部屋）" style="max-width:320px;margin:0">
        <button class="tbtn" data-set="roomAdd">追加</button></div>

      <div class="sec">接続</div>
      <div class="note">このアプリのアドレス：${esc(location.origin + location.pathname)}<br>
      ${location.protocol === "https:" ? "家の外からもこのアドレスで使えます。" : "http のアドレスです。音声入力と外出先での利用には https のアドレス（Home Assistant Cloud のリモートURL）で開いてください。"}</div>

      <div class="sec">音声</div>
      <label class="row"><span class="rt">呼びかけで反応する<div class="sub">Home Assistant の音声アシスタントに設定したウェイクワードで反応します。この画面を表示している間だけ待ち受けます。</div></span>
        <input type="checkbox" data-set="wake" ${S.wake ? "checked" : ""}></label>
      ${S.pipelines.length ? `<div class="note" style="margin-top:8px">使うアシスタント（AI の会話エージェントはここで選んだものが答えます）</div>` + S.pipelines.map((p) => `
        <label class="row"><input type="radio" name="pl" data-set="pipeline" data-id="${esc(p.id)}" ${p.id === pid ? "checked" : ""}>
        <span class="rt">${esc(p.name)}<div class="sub">${esc([p.id === S.preferred ? "優先" : null, p.wake ? "呼びかけ対応" : "呼びかけ未設定", p.agent || null].filter(Boolean).join("・"))}</div></span></label>`).join("") : ""}

      ${ws.length > 1 ? `<div class="sec">天気</div>` + ws.map((x) => `
        <label class="row"><input type="radio" name="wx" data-set="weather" data-id="${esc(x.entity_id)}" ${w && x.entity_id === w.entity_id ? "checked" : ""}>
        <span class="rt">${esc(nameOf(x))}<div class="sub">${esc(x.entity_id)}</div></span></label>`).join("") : ""}

      <div class="sec" id="selTitle"></div>
      <input class="search" type="search" placeholder="名前で絞り込み" data-set="query">
      <div><button class="tbtn" data-set="all">すべて選択</button><button class="tbtn" data-set="none">すべて解除</button></div>
      <div id="selList"></div>

      <div class="sec">アプリの更新</div><div data-update-slot></div>
      <div class="sec">アカウント</div>
      ${accountHTML()}
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
      "質問には AI が答えて読み上げます。5分以内なら続けて質問できます。",
      "下の入力欄に文字で書いて送信しても同じように使えます。",
      "音声入力は https のアドレス（Home Assistant Cloud のリモートURL）で開いたときに使えます。",
    ]],
    ["🗣️ 呼びかけで反応", [
      "⚙ 設定 →「呼びかけで反応する」をオンにすると、呼びかけるだけで聞き取りを始めます（ピッと鳴ります）。",
      "この画面を表示している間だけ待ち受けます。iPad の「設定 → 画面表示と明るさ → 自動ロック」を「しない」にすると便利です。",
    ]],
    ["⚙ 表示する機器を選ぶ", [
      "⚙ 設定 →「表示する機器」で、チェックを付けた機器だけを表示します。名前で絞り込めます。",
    ]],
    ["🚪 部屋ごとに分ける", [
      "機器は Home Assistant のエリアごとに分けて表示されます。上の「すべて／リビング／寝室…」で部屋を絞り込めます。",
      "⚙ 設定 →「部屋」で、アプリだけの部屋を追加できます。「表示する機器」の各機器の「🏠」で部屋を変えられます。",
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
    ["🎨 配色", ["⚙ 設定 →「配色」で、11種類の色の組み合わせから選べます。"]],
    ["📱 ホーム画面に追加", [
      "Safari でこのページを開き、共有ボタン（□↑）→「ホーム画面に追加」を選ぶと、アプリのように使えます。",
    ]],
    ["⬆️ アップデート", [
      "新しいバージョンが出ると、画面上部に「更新する」ボタンが表示されます。押すと Home Assistant が自動で新しいファイルに入れ替えて、画面を読み込み直します。",
      "この機能には Home Assistant 側に shell_command.update_homepanel の設定が必要です（README 参照）。",
    ]],
    ["❓ 困ったとき", [
      "接続できない：Home Assistant が動いているか、ネットワークにつながっているか確認してください。",
      "「呼びかけ検知が設定されていません」：Home Assistant の音声アシスタントでウェイクワードを設定してください。",
      "マイクが使えない：iPad の「設定 → アプリ → Safari → マイク」が許可になっているか確認してください。",
      "ログインし直したいとき：⚙ 設定 → ログアウト。",
    ]],
  ];
  function helpHTML() {
    return `<div class="wrap help"><div class="pagebar"><button class="tbtn" data-set="close">← 戻る</button><h2>使い方</h2></div>
      ${HELP.map(([t, items]) => `<div class="card"><h4>${esc(t)}</h4><ul>${items.map((i) => `<li>${esc(i)}</li>`).join("")}</ul></div>`).join("")}
      <div data-update-slot></div>
      <div style="text-align:center;margin:20px 0 40px"><button class="btn" data-set="close">閉じる</button></div></div>`;
  }

  function openPage(name) {
    S.page = name;
    const page = $("#page");
    page.classList.remove("hidden");
    page.scrollTop = 0;
    if (name === "auto") { S.edit = null; S.autoMsg = null; renderAuto(); loadRules(); return; }
    page.innerHTML = name === "settings" ? settingsHTML() : helpHTML();
    if (name === "settings") renderSelList();
    renderUpdate();
  }
  /** 設定ページを開いたまま描き直す（スクロール位置は保つ） */
  function openPageKeep() {
    if (S.page !== "settings") return;
    const page = $("#page");
    const y = page.scrollTop;
    page.innerHTML = settingsHTML();
    renderSelList();
    renderUpdate();
    page.scrollTop = y;
  }
  function closePage() {
    if (S.page === "help") store.set("helpShown", true);
    S.page = null;
    S.edit = null;
    $("#page").classList.add("hidden");
    $("#page").innerHTML = "";
  }

  async function onSetting(el) {
    if (S.page === "auto" && el.dataset.set !== "close" && (await onAutoSetting(el))) return;
    switch (el.dataset.set) {
      case "theme": S.theme = el.dataset.id; store.set("theme", S.theme); applyTheme(); openPageKeep(); break;
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
    return S.haAreas.concat(Object.entries(S.customRooms).sort((a, b) => a[0].localeCompare(b[0])).map(([key, name]) => ({ key, name, ha: false })));
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
      devs.forEach((d) => { if (d.area_id) devArea[d.id] = d.area_id; });
      const ents = await ha.send({ type: "config/entity_registry/list" });
      const map = {};
      ents.forEach((e) => { const a = e.area_id || (e.device_id && devArea[e.device_id]); if (a) map[e.entity_id] = a; });
      S.entityAreas = map;
      renderEntities();
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
    return { id: (schedule ? "hp_sched_" : "hp_auto_") + Date.now(), name: "", schedule, trigger: { kind: "time", at: "07:00" }, weekdays: [], after: null, before: null, actions: [], isNew: true };
  }

  function opsFor(d) {
    switch (d) {
      case "light": return [["turn_on", "つける"], ["turn_on", "明るさを指定してつける", "brightness_pct"], ["turn_off", "消す"], ["toggle", "切り替える"]];
      case "switch": case "input_boolean": case "fan": return [["turn_on", "オンにする"], ["turn_off", "オフにする"], ["toggle", "切り替える"]];
      case "climate": return [["set_hvac_mode", "運転モードを変える", "hvac_mode"], ["set_temperature", "温度を設定する", "temperature"], ["turn_off", "停止する"]];
      case "cover": return [["open_cover", "開ける"], ["close_cover", "閉める"], ["set_cover_position", "開き具合を指定する", "position"]];
      case "scene": case "script": return [["turn_on", "実行する"]];
      default: return [["turn_on", "オンにする"], ["turn_off", "オフにする"]];
    }
  }
  const PARAM_DEFAULT = { brightness_pct: 100, temperature: 25, hvac_mode: "cool", position: 50 };
  function opIndex(a) {
    const d = a.entity.split(".")[0], svc = a.service.split(".")[1];
    const ops = opsFor(d);
    let i = ops.findIndex((o) => o[0] === svc && o[2] && a.data[o[2]] !== undefined);
    if (i < 0) i = ops.findIndex((o) => o[0] === svc && !o[2]);
    if (i < 0) i = ops.findIndex((o) => o[0] === svc);
    return i;
  }
  function makeAct(entity, op) {
    const d = entity.split(".")[0];
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
  const entName = (id) => (S.ha && S.ha.states[id] ? nameOf(S.ha.states[id]) : id);
  const DOMAIN_LABEL2 = Object.assign({}, DOMAIN_LABEL, { binary_sensor: "センサー", person: "人", device_tracker: "位置", lock: "鍵" });

  function actLabel(a) {
    const d = a.entity.split(".")[0];
    const op = opsFor(d)[opIndex(a)];
    const n = entName(a.entity);
    if (op && op[2] === "brightness_pct") return `${n} を明るさ ${a.data.brightness_pct}% でつける`;
    if (op && op[2] === "temperature") return `${n} を ${a.data.temperature}℃ に設定する`;
    if (op && op[2] === "hvac_mode") return `${n} を${hvacLabel(a.data.hvac_mode)}にする`;
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
  function trigLabel(t) {
    if (t.kind === "time") return `${t.at.replace(/^0(\d)/, "$1")} に`;
    if (t.kind === "state") {
      const o = stateOptions(t.entity.split(".")[0]).find((x) => x[0] === t.to);
      return `${entName(t.entity)} が${o ? o[1] : `「${t.to}」になった`}とき`;
    }
    const base = t.event === "sunrise" ? "日の出" : "日の入り";
    return base + (t.offset < 0 ? `の${-t.offset}分前に` : t.offset > 0 ? `の${t.offset}分後に` : "に");
  }
  function ruleSummary(r) {
    const time = [weekdayText(r.weekdays), r.after || r.before ? `${r.after || "0:00"}〜${r.before || "24:00"} の間` : null].filter(Boolean).join("・");
    return `${time} ${trigLabel(r.trigger)} → ${r.actions.map(actLabel).join("、") || "（操作なし）"}`;
  }

  function toConfig(r) {
    const t = r.trigger;
    let trig;
    if (t.kind === "time") trig = { trigger: "time", at: hhmm(t.at) + ":00" };
    else if (t.kind === "state") trig = { trigger: "state", entity_id: t.entity, to: t.to };
    else {
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
    return {
      id: r.id, alias: r.name, description: "ホームパネルで作成", mode: "single",
      triggers: [trig], conditions: conds,
      actions: r.actions.map((a) => ({ action: a.service, target: { entity_id: a.entity }, data: a.data })),
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
    else return null;
    const conds = asList(j.conditions || j.condition) || [];
    if (conds.length > 1) return null;
    let weekdays = [], after = null, before = null;
    if (conds.length === 1) {
      const c = conds[0];
      if (c.condition !== "time") return null;
      weekdays = asList(c.weekday) || [];
      after = c.after ? hhmm(c.after) : null;
      before = c.before ? hhmm(c.before) : null;
    }
    const acts = asList(j.actions || j.action);
    if (!acts) return null;
    const actions = [];
    for (const a of acts) {
      const svc = a.action || a.service;
      if (!svc || !svc.includes(".")) return null;
      const data = Object.assign({}, a.data || a.service_data || {});
      const entity = single(a.target && a.target.entity_id) || single(data.entity_id) || single(a.entity_id);
      if (!entity) return null;
      delete data.entity_id;
      actions.push({ entity, service: svc, data });
    }
    return { id, name: j.alias || "", schedule: isSched(id), trigger, weekdays, after, before, actions };
  }

  function automations() {
    return S.ha ? Object.values(S.ha.states).filter((e) => e.entity_id.startsWith("automation.")).sort((a, b) => nameOf(a).localeCompare(nameOf(b), "ja")) : [];
  }

  async function loadRules() {
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
      ${can ? "" : `<div class="info-box">作成・編集には管理者の権限が必要です。⚙ 設定 → アカウント で「管理者ログインを追加」してください。オン/オフと今すぐ実行は、このままでも使えます。</div>`}
      ${S.autoMsg ? `<div class="info-box">${esc(S.autoMsg)}</div>` : ""}
      <div style="margin:12px 0"><button class="btn" data-set="newRule" ${can ? "" : "disabled"}>${tab === 0 ? "＋ 新しいスケジュール" : "＋ 新しいオートメーション"}</button></div>
      ${list.length ? "" : `<div class="note" style="padding:16px">${tab === 0 ? "スケジュール" : "オートメーション"}はまだありません。</div>`}
      ${list.map((a) => {
        const id = a.attributes.id, rule = S.rules[id];
        const sum = rule ? ruleSummary(rule) : isAppRule(id) ? "（内容を読み込み中、または読み込めません）" : "Home Assistant で作成";
        const last = a.attributes.last_triggered ? "最後に実行：" + String(a.attributes.last_triggered).slice(0, 16).replace("T", " ") : "";
        return `<div class="auto" data-set="editRule" data-id="${esc(a.entity_id)}"><div class="at"><div class="an">${esc(nameOf(a))}</div>
          <div class="as">${esc(sum)}</div>${last ? `<div class="as">${esc(last)}</div>` : ""}</div>
          <button class="tbtn" data-set="runAuto" data-id="${esc(a.entity_id)}">今すぐ実行</button>
          <label class="sw"><input type="checkbox" data-set="autoOn" data-id="${esc(a.entity_id)}" ${a.state === "on" ? "checked" : ""}><span></span></label></div>`;
      }).join("")}</div>`;
  }

  function entityOptions(domains, selected) {
    if (!S.ha) return "";
    const groups = {};
    Object.values(S.ha.states).filter((e) => domains.includes(dom(e))).forEach((e) => { (groups[dom(e)] = groups[dom(e)] || []).push(e); });
    return domains.filter((d) => groups[d]).map((d) => `<optgroup label="${esc(DOMAIN_LABEL2[d] || d)}">${groups[d]
      .sort((a, b) => nameOf(a).localeCompare(nameOf(b), "ja"))
      .map((e) => `<option value="${esc(e.entity_id)}" ${e.entity_id === selected ? "selected" : ""}>${esc(nameOf(e))}</option>`).join("")}</optgroup>`).join("");
  }

  function editorHTML() {
    const r = S.edit;
    const t = r.trigger;
    const chip = (sel, set, id, label) => `<button class="chip${sel ? " sel" : ""}" data-set="${set}" data-id="${esc(id)}">${esc(label)}</button>`;
    let trig = "";
    if (!r.schedule) {
      trig += `<div class="chips">${chip(t.kind === "time", "trigKind", "time", "時刻")}${chip(t.kind === "state", "trigKind", "state", "機器の状態")}
        ${chip(t.kind === "sun" && t.event === "sunrise", "trigKind", "sunrise", "日の出")}${chip(t.kind === "sun" && t.event === "sunset", "trigKind", "sunset", "日の入り")}</div>`;
    }
    if (t.kind === "time") trig += `<input type="time" data-set="trigAt" value="${esc(t.at)}">`;
    else if (t.kind === "state") {
      trig += `<select data-set="trigEntity"><option value="">機器を選ぶ…</option>${entityOptions(TRIGGER_DOMAINS, t.entity)}</select>`;
      if (t.entity) trig += `<div class="chips">${stateOptions(t.entity.split(".")[0]).map(([v, l]) => chip(t.to === v, "trigTo", v, l)).join("")}</div>`;
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
      if (op && op[2] === "temperature") param = `<div class="stepper"><span class="sl">温度</span><button class="tb" data-set="actTemp" data-id="${i}" data-d="-0.5">−</button><span class="tv">${fmt(Number(a.data.temperature))}℃</span><button class="tb" data-set="actTemp" data-id="${i}" data-d="0.5">＋</button></div>`;
      if (op && op[2] === "hvac_mode") {
        const ent = S.ha && S.ha.states[a.entity];
        const modes = ((ent && ent.attributes.hvac_modes) || ["cool", "heat", "dry", "fan_only", "auto"]).filter((m) => m !== "off");
        param = `<div class="chips">${modes.map((m) => `<button class="chip${a.data.hvac_mode === m ? " sel" : ""}" data-set="actMode" data-id="${i}" data-v="${esc(m)}">${esc(hvacLabel(m))}</button>`).join("")}</div>`;
      }
      return `<div class="act"><div class="head"><div class="ht"><div class="dom">${esc(DOMAIN_LABEL2[d] || d)}</div><div class="name">${esc(entName(a.entity))}</div></div>
        <button class="tbtn" data-set="actDel" data-id="${i}">削除</button></div>
        <div class="chips">${ops.map((o, j) => `<button class="chip${j === oi ? " sel" : ""}" data-set="actOp" data-id="${i}" data-j="${j}">${esc(o[1])}</button>`).join("")}</div>${param}</div>`;
    }).join("");
    return `<div class="wrap"><div class="pagebar"><button class="tbtn" data-set="editCancel">← キャンセル</button>
        <h2>${r.isNew ? "新しい" : ""}${r.schedule ? "スケジュール" : "オートメーション"}</h2></div>
      <div class="field"><div class="fl">名前</div><input type="text" data-set="ruleName" value="${esc(r.name)}" placeholder="空欄なら内容から自動で付けます"></div>
      <div class="field"><div class="fl">${r.schedule ? "時刻" : "きっかけ"}</div>${trig}</div>
      <div class="field"><div class="fl">曜日（選ばなければ毎日）</div>${days}</div>
      ${range}
      <div class="field"><div class="fl">実行すること</div>${acts}
        <select data-set="actAdd"><option value="">＋ 操作を追加（機器を選ぶ）…</option>${entityOptions(SUPPORTED, "")}</select></div>
      <div class="summary" id="ruleSummary">内容：${esc(ruleSummary(r))}</div>
      ${S.editError ? `<div class="warn">${esc(S.editError)}</div>` : ""}
      <div class="brow" style="margin:12px 0 40px"><button class="btn" data-set="ruleSave" ${S.saving ? "disabled" : ""}>${S.saving ? "保存しています…" : "保存"}</button>
        ${r.isNew ? "" : `<button class="btn outline" data-set="ruleDelete">削除</button>`}</div></div>`;
  }

  function renderAuto() {
    if (S.page !== "auto") return;
    const page = $("#page");
    const y = page.scrollTop;
    page.innerHTML = S.edit ? editorHTML() : autoListHTML();
    page.scrollTop = y;
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
        else r.trigger = { kind: "sun", event: id, offset: 0 };
        break;
      case "trigAt": r.trigger.at = el.value || "07:00"; break;
      case "trigEntity": r.trigger = { kind: "state", entity: el.value, to: el.value ? stateOptions(el.value.split(".")[0])[0][0] : "on" }; break;
      case "trigTo": r.trigger.to = id; break;
      case "sunOff": r.trigger.offset += Number(id); break;
      case "wd": r.weekdays = r.weekdays.includes(id) ? r.weekdays.filter((d) => d !== id) : r.weekdays.concat([id]); break;
      case "wdPreset": r.weekdays = id === "weekday" ? ["mon", "tue", "wed", "thu", "fri"] : id === "weekend" ? ["sat", "sun"] : []; break;
      case "rangeOn": if (el.checked) { r.after = "18:00"; r.before = "23:00"; } else { r.after = null; r.before = null; } break;
      case "after": r.after = el.value || null; break;
      case "before": r.before = el.value || null; break;
      case "actAdd": if (el.value) r.actions.push(makeAct(el.value, opsFor(el.value.split(".")[0])[0])); break;
      case "actDel": r.actions.splice(Number(id), 1); break;
      case "actOp": { const a = r.actions[Number(id)]; r.actions[Number(id)] = makeAct(a.entity, opsFor(a.entity.split(".")[0])[Number(el.dataset.j)]); break; }
      case "actTemp": { const a = r.actions[Number(id)]; a.data.temperature = Math.round((Number(a.data.temperature) + Number(el.dataset.d)) * 10) / 10; break; }
      case "actMode": r.actions[Number(id)].data.hvac_mode = el.dataset.v; break;
      case "actParam": {
        const a = r.actions[Number(id)];
        const key = Object.keys(a.data)[0];
        a.data[key] = Number(el.value);
        break;
      }
      case "ruleSave": {
        if (!r.actions.length) { S.editError = "「＋ 操作を追加」で、実行することを1つ以上選んでください"; break; }
        if (r.trigger.kind === "state" && !r.trigger.entity) { S.editError = "きっかけにする機器を選んでください"; break; }
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

  // ================= 画面の組み立て =================
  function setConn(c) {
    S.conn = c;
    const b = $("#badge");
    if (b) {
      const [color, label] = { connected: ["#66bb6a", location.protocol === "https:" ? "接続中（https）" : "接続中"], connecting: ["#ffca28", "接続しています"], retry: ["#ef5350", "再接続待ち"] }[c];
      b.innerHTML = `<span class="dot" style="background:${color}"></span>${label}`;
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
    $("#app").innerHTML = `<div class="wrap">
      <div class="top"><span class="badge" id="badge"></span><span class="sp"></span>
        <button class="tbtn" id="btnHelp">？ 使い方</button><button class="tbtn" id="btnAuto">⏰ 自動化</button><button class="tbtn" id="btnSettings">⚙ 設定</button></div>
      <div id="updateBanner"></div>
      <section class="hero"><div><div class="time" id="time"></div><div class="date" id="date"></div></div><div id="weather"></div></section>
      <section class="card voice">
        <div class="vrow"><button class="mic" id="mic">🎤</button>
          <div style="flex:1;min-width:0"><div class="vstatus" id="vstatus"></div><div class="vreply" id="vreply"></div><div class="vnote" id="vnote"></div></div></div>
        <form class="tform" id="tform"><input type="text" id="tin" placeholder="文字で命令・質問する" enterkeyhint="send" autocomplete="off">
          <button class="tbtn" type="submit">送信</button></form>
      </section>
      <div class="msg hidden" id="msg"></div>
      <div class="roombar" id="roombar"></div>
      <main class="grid" id="grid"></main>
      <div class="note" id="empty" style="text-align:center;padding:32px"></div>
    </div>
    <div class="sheet hidden" id="sheet"><div class="sheet-bg" id="sheetBg"></div>
      <div class="sheet-body"><button class="sheet-close" id="sheetClose" aria-label="閉じる">×</button><div id="sheetContent"></div></div></div>
    <div class="page hidden" id="page"></div>`;

    bindControls($("#grid"));
    bindControls($("#sheetContent"));
    const closeSheet = () => { S.detail = null; $("#sheetContent").dataset.html = ""; renderSheet(); };
    $("#sheetBg").onclick = closeSheet;
    $("#sheetClose").onclick = closeSheet;
    $("#btnHelp").onclick = () => openPage("help");
    $("#btnSettings").onclick = () => openPage("settings");
    $("#btnAuto").onclick = () => openPage("auto");
    $("#mic").onclick = onMicPressed;
    $("#tform").onsubmit = (ev) => { ev.preventDefault(); const t = $("#tin"); sendText(t.value); t.value = ""; t.blur(); };

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
      if (el.dataset.set === "ruleName" && S.edit) S.edit.name = el.value;
      if (el.dataset.set === "actParam" && S.edit) {
        const l = el.parentElement.querySelector(".rl");
        if (l) l.textContent = l.textContent.replace(/\d+%/, el.value + "%");
      }
    });

    // iOS は操作をきっかけにしないと音を出せない・マイクを再開できないので、タップのたびに再開を試みる
    document.addEventListener("pointerdown", () => {
      unlockAudio();
      if (mic.ctx && mic.ctx.state === "suspended") mic.ctx.resume().then(kickWake).catch(() => {});
      else kickWake();
    });

    renderClock();
    setInterval(renderClock, 1000);
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
    if (document.visibilityState === "visible") { keepAwake(); kickWake(); }
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
