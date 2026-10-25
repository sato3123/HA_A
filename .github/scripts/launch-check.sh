#!/bin/bash
# エミュレーターにアプリを入れて起動し、止まらないことと、画面のレイアウトを確認する
#   FORM=phone / tablet（画面写真の名前に付ける）
set -u
PKG=jp.home.panel
FORM=${FORM:-phone}
fail() { echo "::error::[$FORM] $1"; exit 1; }
shot() { adb exec-out screencap -p > "shots/$FORM-$1.png" < /dev/null || true; }
mkdir -p shots

adb install -r app/build/outputs/apk/debug/app-debug.apk < /dev/null || fail "インストールできませんでした"
adb shell pm grant $PKG android.permission.RECORD_AUDIO < /dev/null || true
adb shell settings put system accelerometer_rotation 0 < /dev/null
adb logcat -c < /dev/null

check() {
  local label=$1
  sleep "$2"
  shot "$label"
  adb logcat -d > logcat-$FORM.txt < /dev/null
  if grep -q "FATAL EXCEPTION" logcat-$FORM.txt; then
    grep -A 30 "FATAL EXCEPTION" logcat-$FORM.txt | head -40 | while IFS= read -r l; do echo "::error::$l"; done
    fail "アプリが止まりました（$label）"
  fi
  adb shell pidof $PKG < /dev/null >/dev/null || fail "アプリが動いていません（$label）"
  echo "::notice::[$FORM] $label：アプリは動いています"
}

# 設定ファイルを書き込んで起動し直す（標準入力の内容をそのまま書く）
restart_with_prefs() {
  local content
  content=$(cat)
  adb shell am force-stop $PKG < /dev/null
  printf '%s\n' "$content" | adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ui_prefs.xml'"
  adb shell "run-as $PKG cat shared_prefs/ui_prefs.xml" < /dev/null | grep -q "<map>" || fail "設定ファイルを書き込めませんでした"
  adb logcat -c < /dev/null
  adb shell am start -W -n $PKG/.MainActivity < /dev/null || fail "起動できませんでした"
}

# 画面の文字を探してタップする（見つからなければ 1 を返す）
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml < /dev/null >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml < /dev/null > ui-$FORM.xml
  local xy
  xy=$(python3 - "ui-$FORM.xml" "$1" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1).startswith(sys.argv[2]):
        a, b, c, d = map(int, m.groups()[1:])
        print((a + c) // 2, (b + d) // 2)
        break
PY
)
  [ -n "$xy" ] || return 1
  adb shell input tap $xy < /dev/null
}

# 1. 初回起動（ログイン画面）
adb shell am start -W -n $PKG/.MainActivity < /dev/null || fail "起動できませんでした"
check "1-login" 12

# 2. ECHONET Lite・スクリーンセーバー・呼びかけをオンにして起動
restart_with_prefs <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="el_enabled" value="true" />
    <boolean name="ss_enabled" value="true" />
    <boolean name="wake_enabled" value="true" />
    <boolean name="help_shown" value="true" />
    <string name="theme">light_blue</string>
</map>
X
check "2-features-on" 15

# 3. デモ表示でホーム画面のレイアウトを確認
restart_with_prefs <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="demo_mode" value="true" />
    <boolean name="help_shown" value="true" />
</map>
X
check "3-home" 10
if tap_text "24.3℃"; then
  check "3b-thermo-graph" 3
  adb shell input keyevent 4 < /dev/null
  sleep 2
else
  echo "::warning::[$FORM] 温度計が見つからず、グラフの写真は撮れませんでした"
fi
H=$(adb shell wm size < /dev/null | grep -o '[0-9]*x[0-9]*' | tail -1); W=${H%x*}; HH=${H#*x}
adb shell input swipe $((W/2)) $((HH*8/10)) $((W/2)) $((HH*2/10)) 500 < /dev/null
check "4-scrolled" 3
adb shell input swipe $((W/2)) $((HH*8/10)) $((W/2)) $((HH*2/10)) 500 < /dev/null
check "5-scrolled2" 3
for i in 1 2 3; do
  if tap_text "▶ タップで動画"; then check "5b-camera" 4; adb shell input keyevent 4 < /dev/null; sleep 2; break; fi
  adb shell input swipe $((W/2)) $((HH*3/10)) $((W/2)) $((HH*7/10)) 400 < /dev/null; sleep 2
done

# 4. 向きを変える
adb shell settings put system user_rotation 1 < /dev/null
check "6-rotated" 6
adb shell settings put system user_rotation 0 < /dev/null

# 5. 設定画面（右上の ⚙ ボタン）
adb shell am force-stop $PKG < /dev/null
adb shell am start -W -n $PKG/.MainActivity < /dev/null
sleep 6
adb shell uiautomator dump /sdcard/ui.xml < /dev/null >/dev/null 2>&1
adb shell cat /sdcard/ui.xml < /dev/null > ui-$FORM.xml
B=$(python3 - "ui-$FORM.xml" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1).startswith("⚙"):
        a, b, c, d = map(int, m.groups()[1:])
        print((a + c) // 2, (b + d) // 2)
        break
PY
)
if [ -n "$B" ]; then
  adb shell input tap $B < /dev/null
  check "7-settings" 4
  adb shell input swipe $((W/2)) $((HH*8/10)) $((W/2)) $((HH*2/10)) 500 < /dev/null
  check "8-settings2" 3
  adb shell input swipe $((W/2)) $((HH*3/10)) $((W/2)) $((HH*9/10)) 300 < /dev/null; sleep 1
  if tap_text "部屋と機器"; then check "9-settings-rooms" 3; adb shell input keyevent 4 < /dev/null; sleep 2; fi
  if tap_text "温度計"; then check "10-settings-thermo" 3; fi
else
  echo "::warning::[$FORM] ⚙ ボタンが見つからず、設定画面の写真は撮れませんでした"
fi
echo "::notice::[$FORM] 起動テストはすべて成功しました"
