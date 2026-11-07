// ホームパネル（iPad 版）の Service Worker。
// ・画面の部品（HTML・JS・CSS・アイコン）だけを保存して、起動を速くし、電波が切れても画面が開くようにする
// ・Home Assistant やサーバーの API（/api/・/solar-api/・/camera-api/ など）は、このフォルダの外なので一切さわらない
// ・家の情報（機器の状態・カメラの画像・カレンダーなど）は保存しない
const CACHE = "homepanel-shell-v1";
const SHELL = ["./", "./index.html", "./boot.js", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./apple-touch-icon.png", "./version.json"];
const OK_EXT = /\.(html|js|css|png|webmanifest|json)$/;

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()).catch(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k.startsWith("homepanel-") && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

function cacheable(url) {
  const scope = new URL(self.registration.scope);
  if (url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) return false;
  const rest = url.pathname.slice(scope.pathname.length);
  return rest === "" || (!rest.includes("/") && OK_EXT.test(rest));
}

async function put(req, res) {
  if (!res || !res.ok || res.type !== "basic") return;
  const c = await caches.open(CACHE);
  const url = new URL(req.url);
  if (url.searchParams.has("v")) {
    // 同じファイルの古い版は消す（app.js?v=1.0.90 など）
    for (const k of await c.keys()) {
      const ku = new URL(k.url);
      if (ku.pathname === url.pathname && ku.search !== url.search) await c.delete(k);
    }
  }
  await c.put(req, res);
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (!cacheable(url)) return;
  if (url.searchParams.has("v")) {
    // 版の付いたファイル（app.js?v=… / style.css?v=…）は中身が変わらないので、保存したものを先に使う
    e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((res) => { e.waitUntil(put(req, res.clone())); return res; })));
    return;
  }
  // それ以外（index.html・version.json など）は、まずサーバーから。読めないときだけ保存したものを使う
  e.respondWith(fetch(req, { cache: "no-store" }).then((res) => {
    e.waitUntil(put(req, res.clone()));
    return res;
  }).catch(() => caches.match(req, { ignoreSearch: true }).then((hit) => hit || caches.match("./index.html"))));
});
