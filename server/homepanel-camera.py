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
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


conf = dict({"ha_url": "http://192.168.1.45:8123", "ha_token": "", "cameras": [], "threshold": 2.0}, **load_json(CONF_PATH, {}))
status = {}  # cam id -> {"at", "error", "score"}


def cam_dir(cid):
    return os.path.join(DIR, "cams", "".join(ch for ch in cid if ch.isalnum() or ch in "-_"))


def motion_path(cid):
    return os.path.join(cam_dir(cid), "motion.json")


def ffmpeg():
    return shutil.which("ffmpeg")


def ha_snapshot(entity):
    req = urllib.request.Request(conf["ha_url"].rstrip("/") + "/api/camera_proxy/" + entity,
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
        p, q = self._q()
        if p in ("/", "/status"):
            cams = [{k: v for k, v in c.items() if k != "url"} | {"status": status.get(c["id"])} for c in conf["cameras"]]
            return self._send(200, {"cameras": cams, "ffmpeg": bool(ffmpeg()), "threshold": conf.get("threshold"),
                                    "ha_token_set": bool(conf.get("ha_token")), "keep_hours": KEEP_HOURS})
        if p == "/motion":
            m = load_json(motion_path(q.get("cam", "")), [])
            hours = int(q.get("hours", KEEP_HOURS))
            cut = time.time() - hours * 3600
            return self._send(200, {"motion": [x for x in m if x[0] >= cut], "threshold": conf.get("threshold"),
                                    "sleep": sleep_report(q.get("cam", ""))})
        if p == "/frame":
            cid, t = q.get("cam", ""), int(q.get("t", time.time()))
            # 指定の時刻にいちばん近い画像
            for back in range(0, 30):
                dt = datetime.fromtimestamp(t - back * 60)
                fp = os.path.join(cam_dir(cid), dt.strftime("%Y%m%d"), dt.strftime("%H%M") + ".jpg")
                if os.path.exists(fp):
                    with open(fp, "rb") as f:
                        return self._send(200, f.read(), "image/jpeg")
            return self._send(404, {"error": "画像がありません"})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        p, q = self._q()
        n = int(self.headers.get("Content-Length") or 0)
        try:
            b = json.loads(self.rfile.read(n) or b"{}") if n else {}
        except Exception:
            return self._send(400, {"error": "JSON を読めません"})
        if p == "/config":
            with lock:
                for k in ("ha_url", "ha_token", "threshold"):
                    if k in b:
                        conf[k] = b[k]
                if "cameras" in b:
                    # 送られてきた一覧で置き換える（url を送らなかったカメラは前の url を使う）
                    old = {c["id"]: c for c in conf["cameras"]}
                    new = []
                    for c in b["cameras"]:
                        if not c.get("id"):
                            continue
                        if c.get("kind") == "rtsp" and not c.get("url") and c["id"] in old:
                            c["url"] = old[c["id"]].get("url", "")
                        new.append(c)
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
