#!/usr/bin/env python3
"""
カメラの記録と動きの分析（ホームパネルのサーバー・192.168.1.46 で動かす）。

・登録したカメラから1分ごとに、小さな画像（幅320）を1枚保存する（2日分。古いものから消す）。
・同じときに数秒間の映像を小さく読み、前後のコマの違いから「動きの量」を記録する。
・夜に動きが止まった時刻（寝た時刻の目安）と、朝に動き出した時刻（起きた時刻の目安）を計算する。

カメラは2種類：
  rtsp … ネットワークカメラに直接（rtsp://ユーザー:パスワード@IP:554/...）。ffmpeg で読む
  ha   … Home Assistant のカメラ（camera.xxx）。Home Assistant の静止画（camera_proxy）を読む
画面（Android 版）からは /camera-api/ で登録・確認する（家の中のネットワークからだけ）。
必要なもの：ffmpeg（セットアップで入れる）
"""
import json
import re
import os
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ["TZ"] = "Asia/Tokyo"
time.tzset()
# 作るファイルは、このサービスだけが読めるようにする（設定に AiSEG2 のパスワードや鍵が入るため）
os.umask(0o077)

DIR = os.environ.get("CAMERA_DIR", "/var/lib/homepanel-camera")
CONF_PATH = os.path.join(DIR, "config.json")
API_PORT = int(os.environ.get("CAMERA_API_PORT", "8097"))
KEEP_HOURS = 48
W, H = 64, 36  # 動きを調べるときの大きさ

lock = threading.RLock()


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, data):
    """書きかけで電源が切れても壊れないよう、一時ファイルに書いて fsync してから置き換える。root とこのサービスだけが読める（0600）"""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    try:
        d = os.open(os.path.dirname(path) or ".", os.O_RDONLY)
        os.fsync(d)
        os.close(d)
    except Exception:
        pass


conf = dict({"ha_url": "http://192.168.1.45:8123", "ha_token": "", "cameras": [], "threshold": 2.0}, **load_json(CONF_PATH, {}))
status = {}  # cam id -> {"at", "error", "score"}


def cam_dir(cid):
    return os.path.join(DIR, "cams", "".join(ch for ch in cid if ch.isalnum() or ch in "-_"))


def motion_path(cid):
    return os.path.join(cam_dir(cid), "motion.json")


def ffmpeg():
    return shutil.which("ffmpeg")


def ha_snapshot(entity):
    if not re.fullmatch(r"camera\.[a-z0-9_]+", str(entity)):
        raise RuntimeError("カメラの名前が正しくありません")
    req = urllib.request.Request(ha_base_url() + "/api/camera_proxy/" + entity,
                                 headers={"Authorization": "Bearer " + conf.get("ha_token", "")})
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read()


def run(args, data=None, timeout=25):
    p = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace").strip().splitlines()[-1:] or ["ffmpeg エラー"])
    return p.stdout


def grab(cam):
    """1枚の画像（JPEG・幅320）と、動きを調べる小さな白黒のコマ（数枚）を返す"""
    ff = ffmpeg()
    if not ff:
        raise RuntimeError("ffmpeg が入っていません（サーバーのセットアップをやり直してください）")
    if cam.get("kind") == "rtsp":
        url = cam["url"]
        base = [ff, "-loglevel", "error", "-rtsp_transport", "tcp", "-i", url]
        jpg = run(base + ["-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "6", "-f", "image2", "pipe:1"])
        raw = run(base + ["-t", "4", "-vf", f"fps=2,scale={W}:{H},format=gray", "-f", "rawvideo", "pipe:1"], timeout=30)
    else:
        shots = []
        for i in range(4):
            shots.append(ha_snapshot(cam["entity"]))
            if i < 3:
                time.sleep(1.5)
        jpg = run([ff, "-loglevel", "error", "-i", "pipe:0", "-vf", "scale=320:-2", "-q:v", "6", "-f", "image2", "pipe:1"], data=shots[-1])
        raw = b"".join(run([ff, "-loglevel", "error", "-i", "pipe:0", "-vf", f"scale={W}:{H},format=gray", "-f", "rawvideo", "pipe:1"], data=s) for s in shots)
    n = W * H
    frames = [raw[i:i + n] for i in range(0, len(raw) - n + 1, n)]
    return jpg, frames


def motion_score(frames):
    """前後のコマで、明るさが大きく変わった点の割合（%）の最大値"""
    best = 0.0
    for a, b in zip(frames, frames[1:]):
        changed = sum(1 for x, y in zip(a, b) if abs(x - y) > 18)
        best = max(best, changed * 100.0 / len(a))
    return round(best, 2)


def record(cam):
    cid = cam["id"]
    d = cam_dir(cid)
    now = datetime.now()
    jpg, frames = grab(cam)
    score = motion_score(frames)
    day = os.path.join(d, now.strftime("%Y%m%d"))
    os.makedirs(day, exist_ok=True)
    with open(os.path.join(day, now.strftime("%H%M") + ".jpg"), "wb") as f:
        f.write(jpg)
    with lock:
        m = load_json(motion_path(cid), [])
        m.append([int(time.time()), score])
        cut = time.time() - KEEP_HOURS * 3600
        m = [p for p in m if p[0] >= cut]
        save_json(motion_path(cid), m)
    status[cid] = {"at": time.time(), "error": None, "score": score}


def prune():
    cut = (datetime.now() - timedelta(hours=KEEP_HOURS + 24)).strftime("%Y%m%d")
    root = os.path.join(DIR, "cams")
    if not os.path.isdir(root):
        return
    for cam in os.listdir(root):
        for day in os.listdir(os.path.join(root, cam)):
            p = os.path.join(root, cam, day)
            if os.path.isdir(p) and day < cut:
                shutil.rmtree(p, ignore_errors=True)
            elif os.path.isdir(p):
                # 2日より古い画像を消す
                limit = time.time() - KEEP_HOURS * 3600
                for f in os.listdir(p):
                    fp = os.path.join(p, f)
                    if os.path.getmtime(fp) < limit:
                        os.remove(fp)


def worker(cam):
    while True:
        cur = next((c for c in conf["cameras"] if c["id"] == cam["id"]), None)
        if cur is None or not cur.get("record", True):
            return
        t0 = time.time()
        try:
            record(cur)
        except Exception as e:
            status[cur["id"]] = {"at": time.time(), "error": str(e)[:200], "score": None}
        time.sleep(max(5, 60 - (time.time() - t0)))


workers = {}


def ensure_workers():
    for c in conf["cameras"]:
        if c.get("record", True) and (c["id"] not in workers or not workers[c["id"]].is_alive()):
            t = threading.Thread(target=worker, args=(c,), daemon=True)
            workers[c["id"]] = t
            t.start()


def sleep_report(cid):
    """夜ごとの、寝た時刻（19時〜翌3時に、動きが30分以上止まり始めた時刻）と起きた時刻（4時〜11時に、動きが続き始めた時刻）の目安"""
    m = load_json(motion_path(cid), [])
    th = float(conf.get("threshold", 2.0))
    out = []
    if not m:
        return out
    by_min = {p[0] // 60: p[1] for p in m}
    first = datetime.fromtimestamp(m[0][0]).date()
    last = datetime.fromtimestamp(m[-1][0]).date()
    day = first - timedelta(days=1)
    while day <= last:
        night_start = datetime.combine(day, datetime.min.time()) + timedelta(hours=19)
        night_end = night_start + timedelta(hours=8)  # 翌3時
        morning_end = night_start + timedelta(hours=16)  # 翌11時
        slept = woke = None
        t = night_start
        while t < night_end:
            k = int(t.timestamp()) // 60
            window = [by_min.get(k + i) for i in range(30)]
            known = [v for v in window if v is not None]
            if len(known) >= 20 and max(known) < th:
                slept = t
                break
            t += timedelta(minutes=5)
        if slept:
            t = max(slept + timedelta(hours=3), night_start + timedelta(hours=9))  # 早くても4時から
            while t < morning_end:
                k = int(t.timestamp()) // 60
                window = [by_min.get(k + i) for i in range(10)]
                known = [v for v in window if v is not None]
                if len(known) >= 6 and sum(1 for v in known if v >= th) >= 4:
                    woke = t
                    break
                t += timedelta(minutes=5)
        if slept or woke:
            out.append({"night": day.isoformat(), "slept": slept.strftime("%H:%M") if slept else None,
                        "woke": woke.strftime("%H:%M") if woke else None})
        day += timedelta(days=1)
    return out



# ================= 画面からの操作の確認（Home Assistant にログインしている人だけ） =================
# 家の中のネットワークの、ほかの機器（来客のスマートフォン・家電など）から勝手に操作されないように、
# 画面（アプリ・iPad）が送ってくる Home Assistant のアクセストークンを、Home Assistant に問い合わせて確かめる。
HA_BASE_ENV = os.environ.get("HOMEPANEL_HA_URL", "").rstrip("/")
_auth_cache = {}


def ha_base_url():
    return HA_BASE_ENV or str(conf.get("ha_url") or "").rstrip("/")


# 認証の結果を覚えておく時間（秒）。一般の操作は2分、管理の操作は1分（Home Assistant でトークンを消したら、すぐ使えなくするため）
AUTH_TTL_USER = 120
AUTH_TTL_ADMIN = 60
_admin_cache = {}


def _ha_get_status(path, tok):
    """Home Assistant に path を GET して、HTTP の状態コードを返す（読めなければ 0）。本文は読まない"""
    try:
        req = urllib.request.Request(ha_base_url() + path, headers={"Authorization": "Bearer " + tok})
        with urllib.request.urlopen(req, timeout=6) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def _cache_put(cache, k):
    if len(cache) >= 100:
        # 古いものから消す
        for old in sorted(cache, key=cache.get)[:50]:
            cache.pop(old, None)
    cache[k] = time.time()


def check_ha_token(tok, admin=False):
    """Home Assistant のアクセストークンが使えるか（admin=True なら、Home Assistant の管理者のものか）を確かめる。
    使えるかは /api/ が 200 を返すか、管理者かは管理者だけが読める /api/error_log が 200 を返すかで決める"""
    import hashlib
    if not tok or len(tok) > 4096 or not ha_base_url():
        return False
    k = hashlib.sha256(tok.encode()).hexdigest()
    t = _auth_cache.get(k)
    if not (t and time.time() - t < AUTH_TTL_USER):
        if _ha_get_status("/api/", tok) != 200:
            _auth_cache.pop(k, None)
            _admin_cache.pop(k, None)
            return False
        _cache_put(_auth_cache, k)
    if not admin:
        return True
    t = _admin_cache.get(k)
    if t and time.time() - t < AUTH_TTL_ADMIN:
        return True
    if _ha_get_status("/api/error_log", tok) == 200:
        _cache_put(_admin_cache, k)
        return True
    return False


# このサーバーの中での作業（コンソールでの確認など）用のトークン。root と このサービスだけが読める（0600）
LOCAL_TOKEN_PATH = os.path.join(DIR, "local_token")


def local_token():
    try:
        with open(LOCAL_TOKEN_PATH, encoding="utf-8") as f:
            v = f.read().strip()
        if len(v) >= 32:
            return v
    except Exception:
        pass
    import secrets
    v = secrets.token_urlsafe(32)
    os.makedirs(DIR, exist_ok=True)
    fd = os.open(LOCAL_TOKEN_PATH + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(v)
    os.replace(LOCAL_TOKEN_PATH + ".tmp", LOCAL_TOKEN_PATH)
    return v


def bearer(handler):
    """Authorization: Bearer のトークン。URL のクエリのトークンは受け付けない（履歴・記録に残るため）"""
    h = handler.headers.get("Authorization") or ""
    return h[7:].strip() if h.lower().startswith("bearer ") else ""


def request_authorized(handler, admin=False):
    """全部の窓口で認証が要る（転送のヘッダーの有無では決めない）。
    使えるのは、Home Assistant のアクセストークン（admin=True のときは管理者のもの）か、このサーバーの中だけで読めるローカルのトークン"""
    import hmac
    tok = bearer(handler)
    if not tok:
        return False
    if hmac.compare_digest(tok.encode(), local_token().encode()):
        return True
    return check_ha_token(tok, admin)


class Api(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _q(self):
        u = urllib.parse.urlparse(self.path)
        return u.path.replace("/camera-api", "", 1).rstrip("/") or "/", {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}

    def do_GET(self):
        if not request_authorized(self):
            return self._send(401, {"error": "Home Assistant にログインしているアプリ・画面からだけ使えます"})
        p, q = self._q()
        if p in ("/", "/status"):
            cams = [{k: v for k, v in c.items() if k != "url"} | {"status": status.get(c["id"])} for c in conf["cameras"]]
            return self._send(200, {"cameras": cams, "ffmpeg": bool(ffmpeg()), "threshold": conf.get("threshold"),
                                    "ha_token_set": bool(conf.get("ha_token")), "keep_hours": KEEP_HOURS})
        cid = q.get("cam", "")
        if p in ("/motion", "/frame") and not any(c["id"] == cid for c in conf["cameras"]):
            return self._send(404, {"error": "カメラが登録されていません"})
        if p == "/motion":
            m = load_json(motion_path(cid), [])
            try:
                hours = max(1, min(KEEP_HOURS, int(q.get("hours", KEEP_HOURS))))
            except ValueError:
                return self._send(400, {"error": "hours が正しくありません"})
            cut = time.time() - hours * 3600
            return self._send(200, {"motion": [x for x in m if x[0] >= cut], "threshold": conf.get("threshold"),
                                    "sleep": sleep_report(q.get("cam", ""))})
        if p == "/frame":
            try:
                t = int(q.get("t", time.time()))
            except ValueError:
                return self._send(400, {"error": "t が正しくありません"})
            # 記録が残っている範囲（保存の時間＋少し）だけ
            if not (time.time() - (KEEP_HOURS + 1) * 3600 <= t <= time.time() + 120):
                return self._send(404, {"error": "画像がありません"})
            # 指定の時刻にいちばん近い画像
            for back in range(0, 30):
                dt = datetime.fromtimestamp(t - back * 60)
                fp = os.path.join(cam_dir(cid), dt.strftime("%Y%m%d"), dt.strftime("%H%M") + ".jpg")
                if os.path.exists(fp):
                    with open(fp, "rb") as f:
                        return self._send(200, f.read(), "image/jpeg")
            return self._send(404, {"error": "画像がありません"})
        self._send(404, {"error": "not found"})

    MAX_BODY = 64 * 1024
    timeout = 20

    def do_POST(self):
        if not request_authorized(self):
            return self._send(401, {"error": "Home Assistant にログインしているアプリ・画面からだけ使えます"})
        p, q = self._q()
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return self._send(400, {"error": "Content-Length が正しくありません"})
        if n < 0:
            return self._send(400, {"error": "Content-Length が正しくありません"})
        if n > self.MAX_BODY:
            return self._send(413, {"error": "送られてきたデータが大きすぎます"})
        try:
            b = json.loads(self.rfile.read(n)) if n else {}
        except Exception:
            return self._send(400, {"error": "JSON を読めません"})
        if not isinstance(b, dict):
            return self._send(400, {"error": "JSON のオブジェクトを送ってください"})
        # カメラの登録・鍵の設定は Home Assistant の管理者だけ（カメラの URL にはパスワードが入っていることがあるため）
        if p == "/config" and not request_authorized(self, admin=True):
            return self._send(403, {"error": "カメラの設定は Home Assistant の管理者だけができます"})
        if p == "/config":
            with lock:
                # Home Assistant のアドレスは画面からは変えない（鍵をほかの場所に送らされないように）
                for k in (("ha_token", "threshold") if HA_BASE_ENV else ("ha_url", "ha_token", "threshold")):
                    if k in b:
                        conf[k] = b[k]
                if "cameras" in b:
                    # 送られてきた一覧で置き換える（url を送らなかったカメラは前の url を使う）
                    old = {c["id"]: c for c in conf["cameras"]}
                    new = []
                    bad = []
                    for c in b["cameras"]:
                        if not isinstance(c, dict) or not c.get("id"):
                            bad.append("（名前の無いカメラ）")
                            continue
                        # 読む先は RTSP のカメラか、Home Assistant のカメラだけ（ほかのファイルやサーバーを読ませない）
                        if c.get("kind") == "rtsp" and c.get("url") and not re.match(r"rtsps?://", str(c["url"])):
                            bad.append(f"{c.get('name') or c['id']}（アドレスは rtsp:// で始めてください）")
                            continue
                        if c.get("kind") != "rtsp" and not re.fullmatch(r"camera\.[a-z0-9_]+", str(c.get("entity", ""))):
                            bad.append(f"{c.get('name') or c['id']}（Home Assistant のカメラ camera.〇〇 を選んでください）")
                            continue
                        if c.get("kind") == "rtsp" and not c.get("url") and c["id"] in old:
                            c["url"] = old[c["id"]].get("url", "")
                        new.append(c)
                    if bad:
                        # 黙って捨てずに、保存できなかったカメラを知らせる（何も変えない）
                        return self._send(400, {"error": "保存できないカメラがあります：" + "、".join(bad)})
                    conf["cameras"] = new
                save_json(CONF_PATH, conf)
            ensure_workers()
            return self.do_GET_status()
        if p == "/test":
            cam = next((c for c in conf["cameras"] if c["id"] == b.get("cam")), None)
            if not cam:
                return self._send(404, {"error": "カメラが登録されていません"})
            try:
                record(cam)
                return self._send(200, {"ok": True, "score": status[cam["id"]]["score"]})
            except Exception as e:
                return self._send(200, {"ok": False, "error": str(e)[:300]})
        self._send(404, {"error": "not found"})

    def do_GET_status(self):
        self.path = "/camera-api/status"
        return self.do_GET()


def main():
    os.makedirs(DIR, exist_ok=True)
    local_token()  # このサーバーの中での作業用のトークンを、先に作っておく（homepanel-api が読む）
    ensure_workers()

    def janitor():
        while True:
            try:
                prune()
                ensure_workers()
            except Exception as e:
                print("片付けでエラー:", e, flush=True)
            time.sleep(1800)
    threading.Thread(target=janitor, daemon=True).start()
    print(f"カメラの記録サービスを開始（画面用 127.0.0.1:{API_PORT}）", flush=True)
    ThreadingHTTPServer(("127.0.0.1", API_PORT), Api).serve_forever()


if __name__ == "__main__":
    main()
