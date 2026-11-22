#!/usr/bin/env bash
# RootTerm APK 构建脚本（在 Ubuntu 24.04 arm64 proot 上，无 Android SDK/Gradle）
#
# 工具链说明：
#   javac 17（apt openjdk-17-jdk-headless）        编译 Java
#   aapt2 36（build-tools 自带，x86_64 静态+动态混合）经 qemu-x86_64 运行  编译/链接资源
#     └ 本机是 arm64，不能直接跑 x86_64 二进制；用 qemu-user + amd64 glibc sysroot
#       （/opt/x86root，由 tools/setup.sh 准备）
#   aapt1（apt aapt，arm64）                       回退：资源链接（配 API 34 框架 jar）
#   d8（build-tools lib/d8.jar，纯 Java）          生成 classes.dex
#   zipalign（apt）                                APK 对齐
#   apksigner（build-tools lib/apksigner.jar，纯 Java）  v2+v3 签名
#
# 产物：dist/RootTerm-2.8.0.apk（包名 com.rootterm.fm，minSdk 26 / targetSdk 37）
#
# 用法：
#   bash build.sh                 # 构建
#   bash build.sh --out /tmp/x.apk
#   bash build.sh --no-aapt2      # 强制用 aapt1 回退链路（compileSdk 记为 34）
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$ROOT"

SDK="tools/sdk"
JAR36="$SDK/android.jar"      # API 36：javac / aapt2 用
JAR34="$SDK/android34.jar"    # API 34：aapt1 回退用
OUT="tools/out"
DIST="dist"
APK="$DIST/RootTerm-2.8.0.apk"
FORCE_AAPT1=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out) APK="$2"; shift 2 ;;
    --out=*) APK="${1#--out=}"; shift ;;
    --no-aapt2) FORCE_AAPT1=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

for f in "$JAR36" "$SDK/d8.jar" "$SDK/apksigner.jar"; do
  [ -f "$f" ] || { echo "缺少 $f，请先跑 bash tools/setup.sh" >&2; exit 1; }
done
[ -f tools/debug.keystore ] || keytool -genkeypair -keystore tools/debug.keystore \
  -storetype PKCS12 -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

QEMU="/usr/bin/qemu-x86_64-static"
SYSPREFIX="/opt/x86root"
AAPT2="$SDK/aapt2"
MODE=""
if [ -z "$FORCE_AAPT1" ] && [ -x "$QEMU" ] && [ -f "$AAPT2" ] && [ -d "$SYSPREFIX/usr/lib/x86_64-linux-gnu" ] \
   && "$QEMU" -L "$SYSPREFIX" "$AAPT2" version >/dev/null 2>&1; then
  MODE="aapt2"
else
  MODE="aapt1"
fi
echo "== 构建模式: $MODE（$([ "$MODE" = aapt2 ] && echo 'compileSdk 36 / aapt2+qemu' || echo 'compileSdk 34 / aapt1 回退')）"

# ---------------------------------------------------------------- 1. 资源
if [ "$MODE" = "aapt2" ]; then
  echo "== [1/7] aapt2 compile + link（API 36 框架 jar）"
  "$QEMU" -L "$SYSPREFIX" "$AAPT2" compile --dir app/src/main/res -o "$OUT/res.zip"
  "$QEMU" -L "$SYSPREFIX" "$AAPT2" link \
    -o "$OUT/base.apk" -I "$JAR36" \
    -A app/src/main/assets \
    --manifest app/src/main/AndroidManifest.xml \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 37 \
    "$OUT/res.zip"
  AAPT_LINK_JAR="$JAR36"
else
  echo "== [1/7] aapt1 package（API 34 框架 jar）"
  [ -f "$JAR34" ] || { echo "缺少 $JAR34（aapt1 回退需要 API 34 框架 jar）" >&2; exit 1; }
  aapt package -f -m -J "$OUT/gen" -M app/src/main/AndroidManifest.xml -S app/src/main/res \
    -A app/src/main/assets \
    -I "$JAR34" -F "$OUT/base.apk" --min-sdk-version 26 --target-sdk-version 37
  AAPT_LINK_JAR="$JAR34"
fi

# ---------------------------------------------------------------- 2. Java
echo "== [2/7] javac（API 36 类库，-source 8 -target 8）"
javac -source 8 -target 8 -bootclasspath "$JAR36" -encoding UTF-8 -proc:none -nowarn -Xlint:none \
  -d "$OUT/classes" $(find app/src/main/java "$OUT/gen" -name '*.java')

# ---------------------------------------------------------------- 3. dex
echo "== [3/7] d8"
java -cp "$SDK/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$JAR36" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# ---------------------------------------------------------------- 4. 打包 dex
echo "== [4/7] 写入 classes.dex"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('  classes.dex ->', dst)
PY

# ---------------------------------------------------------------- 5. 对齐
echo "== [5/7] zipalign"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------------------------------------------------------------- 6. 签名
echo "== [6/7] apksigner（v2+v3）"
java -cp "$SDK/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks tools/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"

# ---------------------------------------------------------------- 7. 校验
echo "== [7/7] 校验"
java -cp "$SDK/apksigner.jar" com.android.apksigner.ApkSignerTool verify --min-sdk-version 26 -v "$APK" \
  | grep -E "Verifies|scheme" || true
BADGING="$(aapt dump badging "$APK")"
echo "$BADGING" | grep -E "^package:|sdkVersion|targetSdkVersion|application-label:|launchable-activity" | head -6
echo "$BADGING" | grep -q "sdkVersion:'26'" || { echo "验收失败: minSdk != 26" >&2; exit 1; }
echo "$BADGING" | grep -q "targetSdkVersion:'37'" || { echo "验收失败: targetSdk != 37" >&2; exit 1; }
ls -l "$APK"
echo "BUILD OK: $ROOT/$APK"
