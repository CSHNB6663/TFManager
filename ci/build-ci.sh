#!/usr/bin/env bash
# CI（x86_64 Linux）原生构建脚本：不用 qemu，直接用 build-tools 的 aapt2/d8/zipalign/apksigner
# 依赖环境变量 ANDROID_HOME（GitHub Actions 默认已设置）
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT_VERSION="${BT_VERSION:-36.0.0}"
BT="$SDK/build-tools/$BT_VERSION"
PLAT="$SDK/platforms/android-36"
JAR="$PLAT/android.jar"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR"; do
  [ -e "$f" ] || { echo "缺少 $f，请先安装 SDK platform 36 + build-tools $BT_VERSION" >&2; exit 1; }
done

OUT="build-out"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

echo "== [1/6] aapt2 compile + link"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
  -A app/src/main/assets \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 37 \
  "$OUT/res.zip"

echo "== [2/6] javac"
javac -source 8 -target 8 -bootclasspath "$JAR" -encoding UTF-8 \
  -proc:none -nowarn -Xlint:none -d "$OUT/classes" \
  $(find app/src/main/java "$OUT/gen" -name '*.java')

echo "== [3/6] d8"
"$BT/d8" --min-api 26 --lib "$JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== [4/6] 打包 dex + 对齐"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== [5/6] 签名"
KS="$OUT/ci.keystore"
keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1

VER_NAME="$(grep -oP 'NAME\s*=\s*"\K[^"]+' app/src/main/java/com/rootterm/fm/AppVersion.java || echo 1.0)"
APK="dist/TFManager-${VER_NAME}.apk"

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --out "$APK" "$OUT/aligned.apk"

echo "== [6/6] 校验"
"$BT/apksigner" verify --min-sdk-version 26 -v "$APK" | grep -E "Verifies|scheme" || true
"$SDK/build-tools/$BT_VERSION/aapt" dump badging "$APK" 2>/dev/null \
  | grep -E "^package:|sdkVersion|targetSdkVersion|application-label" || true
ls -l "$APK"
echo "BUILD OK: $APK"
