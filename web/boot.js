// 本体（app.js / style.css）はバージョン付きで読み込み、更新後に古いキャッシュが使われないようにする
(async () => {
  let v = { version: "dev", code: 0 };
  try {
    const r = await fetch("version.json", { cache: "no-store" });
    if (r.ok) v = await r.json();
  } catch (e) { /* オフライン時などはそのまま */ }
  window.HP_VERSION = v;
  const q = "?v=" + encodeURIComponent(v.version);
  const css = document.createElement("link");
  css.rel = "stylesheet";
  css.href = "style.css" + q;
  document.head.appendChild(css);
  const js = document.createElement("script");
  js.src = "app.js" + q;
  document.body.appendChild(js);
})();

// アプリとして使う（PWA）：画面の部品を保存して起動を速くする。家の情報は保存しない（sw.js）
if ("serviceWorker" in navigator && window.isSecureContext) {
  window.addEventListener("load", () => { navigator.serviceWorker.register("sw.js").catch(() => {}); });
}

// iPad の Safari で開いているときは、ホーム画面への追加のしかたを一度だけ案内する
(() => {
  const ua = navigator.userAgent;
  const ios = /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  const standalone = navigator.standalone === true || (window.matchMedia && matchMedia("(display-mode: standalone)").matches);
  if (!ios || standalone) return;
  let seen = null;
  try { seen = localStorage.getItem("hp_a2hs_seen"); } catch (e) { /* 保存できなければ毎回出す */ }
  if (seen) return;
  const show = () => {
    const d = document.createElement("div");
    d.className = "a2hs";
    d.setAttribute("role", "note");
    d.style.cssText = "position:fixed;left:50%;bottom:calc(16px + env(safe-area-inset-bottom));transform:translateX(-50%);z-index:9999;max-width:min(560px,calc(100% - 32px));" +
      "background:#2b2930;color:#e6e1e5;border:1px solid #49454f;border-radius:16px;padding:14px 16px;font-size:15px;line-height:1.5;box-shadow:0 6px 24px rgba(0,0,0,.4);display:flex;gap:12px;align-items:flex-start";
    const t = document.createElement("div");
    t.innerHTML = "<b>アプリとして使えます</b><br>画面の上の <b>共有ボタン（□に↑）</b> →「<b>ホーム画面に追加</b>」で、ホーム画面のアイコンから全画面で開けます。";
    const b = document.createElement("button");
    b.textContent = "閉じる";
    b.style.cssText = "flex:none;background:#ffb74d;color:#3a2200;font-weight:600;border:0;border-radius:999px;padding:8px 14px;font-size:14px";
    b.onclick = () => { d.remove(); try { localStorage.setItem("hp_a2hs_seen", "1"); } catch (e) { /* なにもしない */ } };
    d.append(t, b);
    document.body.appendChild(d);
  };
  setTimeout(show, 2500);
})();
