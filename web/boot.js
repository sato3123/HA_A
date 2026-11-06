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
