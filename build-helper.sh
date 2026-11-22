#!/usr/bin/env bash
# 构建「暗码助手」小应用（无桌面图标，仅供 KernelSU 模块附带）
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$ROOT"

SDK="tools/sdk"
JAR="$SDK/android.jar"
OUT="tools/out-helper"
APK="dist/TFSecretCode.apk"
QEMU="/usr/bin/qemu-x86_64-static"
SYSPREFIX="/opt/x86root"
AAPT2="$SDK/aapt2"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

echo "== [1/5] aapt2 link"
"$QEMU" -L "$SYSPREFIX" "$AAPT2" link \
  -o "$OUT/base.apk" -I "$JAR" \
  --manifest helper-app/AndroidManifest.xml \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34

echo "== [2/5] javac"
javac -source 8 -target 8 -bootclasspath "$JAR" -encoding UTF-8 -proc:none -nowarn -Xlint:none \
  -g:none -d "$OUT/classes" \
  $(find helper-app/java "$OUT/gen" -name '*.java')

echo "== [3/5] d8"
java -cp "$SDK/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== [4/5] 打包 + 对齐"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/u.apk" <<'PY'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY
zipalign -f -p 4 "$OUT/u.apk" "$OUT/a.apk"

echo "== [5/5] 签名"
java -cp "$SDK/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks tools/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/a.apk"
java -cp "$SDK/apksigner.jar" com.android.apksigner.ApkSignerTool verify --min-sdk-version 26 "$APK"
aapt dump badging "$APK" | grep -E "^package|launchable" | head -3
ls -l "$APK"
echo "HELPER OK: $ROOT/$APK"
