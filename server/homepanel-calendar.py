#!/usr/bin/env python3
"""
TimeTree の予定を、Home Assistant が読めるカレンダーのファイル（.ics）にする（ホームパネルのサーバーで動かす）。

・TimeTree の公式の API は終わっているため、非公式の道具 timetree-exporter（/opt/timetree）を使う。
・30分ごと（homepanel-calendar.timer）に、登録したカレンダーを1つずつ書き出し、
  /var/lib/homepanel-calendar/<名前>.ics に置く。Caddy が家の中だけに http://<このサーバー>/calendar/<名前>.ics で公開する。
・Home Assistant の「Remote Calendar」にその URL を登録すると、calendar.<名前> として予定が見られる。
・TimeTree のメールアドレスとパスワードは /etc/homepanel/timetree.json（root だけが読める）にだけ保存する。

使い方（コンテナの中で root で）：
  homepanel-calendar setup   … メールアドレス・パスワード・カレンダーを登録する（対話式）
  homepanel-calendar run     … いますぐ書き出す（タイマーからも呼ばれる）
  homepanel-calendar status  … 最後に書き出した結果を表示する
"""
import getpass
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import uuid
from datetime import datetime

os.environ["TZ"] = "Asia/Tokyo"
time.tzset()

CONF = "/etc/homepanel/timetree.json"
OUT = "/var/lib/homepanel-calendar"
EXPORTER = "/opt/timetree/bin/timetree-exporter"
STATUS = os.path.join(OUT, "status.json")


def load():
    try:
        with open(CONF, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save(c):
    os.makedirs(os.path.dirname(CONF), exist_ok=True)
    tmp = CONF + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(c, f, ensure_ascii=False, indent=1)
    os.replace(tmp, CONF)
    os.chmod(CONF, 0o600)


def slug(name, used):
    s = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_") or "timetree"
    base, n = s, 2
    while s in used:
        s = f"{base}_{n}"
        n += 1
    return s


def try_list(email, password):
    """カレンダーの一覧（名前とコード）を試しに読む。TimeTree の Web 版と同じ窓口。読めなければ None"""
    try:
        h = {"Content-Type": "application/json", "X-Timetreea": "web/2.1.0/ja"}
        body = json.dumps({"uid": email, "password": password, "uuid": uuid.uuid4().hex}).encode()
        r = urllib.request.urlopen(urllib.request.Request("https://timetreeapp.com/api/v1/auth/email/signin", data=body, headers=h, method="PUT"), timeout=20)
        cookie = "; ".join(c.split(";")[0] for c in r.headers.get_all("Set-Cookie") or [])
        r = urllib.request.urlopen(urllib.request.Request("https://timetreeapp.com/api/v2/calendars?since=0", headers=dict(h, Cookie=cookie)), timeout=20)
        cals = json.load(r).get("calendars") or []
        return [(c.get("alias_code") or str(c.get("id")), c.get("name") or "") for c in cals if not c.get("deactivated_at")]
    except Exception as e:
        print(f"（一覧を自動では読めませんでした：{e}）")
        return None


def setup():
    if os.geteuid() != 0:
        sys.exit("root で実行してください")
    c = load()
    print("TimeTree の予定を Home Assistant に取り込む設定をします。")
    print("メールアドレスとパスワードは、このサーバーの中（root だけが読めるファイル）にだけ保存します。\n")
    email = input(f"TimeTree のメールアドレス{'（今：' + c['email'] + '。Enter でそのまま）' if c.get('email') else ''}: ").strip() or c.get("email", "")
    pw = getpass.getpass("パスワード（入力は表示されません。Enter で今のまま）: ") or c.get("password", "")
    if not email or not pw:
        sys.exit("メールアドレスとパスワードが必要です")
    print("\nカレンダーの一覧を読んでいます…")
    found = try_list(email, pw)
    cals = []
    if found:
        for i, (code, name) in enumerate(found, 1):
            print(f"  {i}. {name}（コード：{code}）")
        sel = input("取り込むカレンダーの番号（複数はカンマ区切り。例：1,3。すべては all）: ").strip().lower()
        idx = range(1, len(found) + 1) if sel in ("all", "a", "*") else [int(x) for x in re.findall(r"\d+", sel)]
        for i in idx:
            if 1 <= i <= len(found):
                cals.append({"code": found[i - 1][0], "name": found[i - 1][1]})
    else:
        print("カレンダーのコードを入力してください。パソコンのブラウザで https://timetreeapp.com にログインし、")
        print("カレンダーを開いたときのアドレス https://timetreeapp.com/calendars/〇〇〇 の 〇〇〇 の部分です。")
        while True:
            code = input("カレンダーのコード（終わりは Enter だけ）: ").strip()
            if not code:
                break
            m = re.search(r"calendars/([^/?#]+)", code)
            code = m.group(1) if m else code
            name = input("  このカレンダーの名前（例：家族）: ").strip() or code
            cals.append({"code": code, "name": name})
    if not cals:
        sys.exit("カレンダーが選ばれていません")
    # ファイル名はカレンダーのコードと、推測できない文字列から決める
    # （家の中のほかの機器から、予定のファイルの名前を当てて読まれないように。設定をやり直しても URL は変わらない）
    import secrets
    secret = c.get("secret") or secrets.token_urlsafe(18).replace("-", "").replace("_", "")[:20]
    used = set()
    for x in cals:
        x["file"] = slug("timetree_" + x["code"], used) + "_" + secret.lower()
        used.add(x["file"])
    save({"email": email, "password": pw, "calendars": cals, "secret": secret})
    print("\n保存しました。いまから書き出します（数分かかることがあります）…")
    ok = run()
    print_urls()
    return ok


def run():
    c = load()
    if not c.get("email") or not c.get("calendars"):
        print("まだ設定されていません。homepanel-calendar setup を実行してください")
        return False
    os.makedirs(OUT, exist_ok=True)
    os.chmod(OUT, 0o755)
    env = dict(os.environ, TIMETREE_EMAIL=c["email"], TIMETREE_PASSWORD=c["password"])
    results = []
    for x in c["calendars"]:
        dst = os.path.join(OUT, x["file"] + ".ics")
        tmp = dst + ".tmp"
        try:
            p = subprocess.run([EXPORTER, "-c", x["code"], "-o", tmp], env=env, stdin=subprocess.DEVNULL,
                               capture_output=True, text=True, timeout=600)
            if p.returncode != 0 or not os.path.exists(tmp):
                raise RuntimeError((p.stderr or p.stdout or f"終了コード {p.returncode}").strip()[-400:])
            with open(tmp, encoding="utf-8", errors="replace") as f:
                n = f.read().count("BEGIN:VEVENT")
            os.chmod(tmp, 0o644)
            os.replace(tmp, dst)
            results.append({"name": x["name"], "events": n, "ok": True})
            print(f"✓ {x['name']}：{n}件")
        except Exception as e:
            if os.path.exists(tmp):
                os.remove(tmp)
            # 前に書き出したファイルは残す（一時的なエラーで予定が消えないように）
            results.append({"name": x["name"], "ok": False, "error": str(e)[:400]})
            print(f"✕ {x['name']}：{e}")
        time.sleep(3)  # TimeTree の負担を減らす
    st = {"at": time.time(), "at_text": datetime.now().strftime("%m/%d %H:%M"), "calendars": results}
    with open(STATUS + ".tmp", "w", encoding="utf-8") as f:
        json.dump(st, f, ensure_ascii=False)
    os.chmod(STATUS + ".tmp", 0o644)
    os.replace(STATUS + ".tmp", STATUS)
    return all(r["ok"] for r in results)


def print_urls():
    ip = os.environ.get("SELF_IP") or subprocess.run(["hostname", "-I"], capture_output=True, text=True).stdout.split()[0]
    print("\nHome Assistant の「設定 → デバイスとサービス → 統合を追加 → Remote Calendar」で、次の URL を1つずつ登録してください：")
    for x in load().get("calendars", []):
        print(f"  {x['name']}：http://{ip}/calendar/{x['file']}.ics")


def status():
    try:
        with open(STATUS, encoding="utf-8") as f:
            st = json.load(f)
    except Exception:
        print("まだ書き出していません")
        return
    print("最後に書き出した時刻：", st.get("at_text"))
    for r in st.get("calendars", []):
        print(("✓ " if r["ok"] else "✕ ") + r["name"] + "：" + (f"{r['events']}件" if r["ok"] else r.get("error", "")))
    print_urls()


if __name__ == "__main__":
    cmd = (sys.argv[1:] or ["run"])[0]
    if cmd == "setup":
        sys.exit(0 if setup() else 1)
    elif cmd == "status":
        status()
    else:
        sys.exit(0 if run() else 1)
