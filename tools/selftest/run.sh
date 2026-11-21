#!/usr/bin/env bash
# RootsTerm 工具链自检：用与 build.sh **相同**的链路打一个最小 APK，确认整条工具链可用。
#   - 资源：优先 aapt2（build-tools 36，x86_64）经 qemu-x86_64 + /opt/x86root 运行；
#           不行则回退 aapt1（apt，arm64）+ tools/sdk/android34.jar（API 34 框架 jar）。
#   - Java：javac 17 + tools/sdk/android.jar（API 36），-source 8 -target 8
#   - dex：d8.jar（纯 Java）；对齐：zipalign；签名：apksigner（apt 优先，否则 apksigner.jar）
# 产物：dist/RootTerm-selftest.apk（minSdk 26 / targetSdk 37，v1+v2+v3 签名）
# 用法：bash tools/selftest/run.sh
set -euo pipefail

SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SELF_DIR/../.." && pwd)"
cd -- "$ROOT"

SDK="tools/sdk"
JAR36="$SDK/android.jar"       # API 36：javac / d8 / aapt2 用
JAR34="$SDK/android34.jar"     # API 34：只为 aapt1 回退准备
D8_JAR="$SDK/d8.jar"
AAPT2="$SDK/aapt2"
QEMU="/usr/bin/qemu-x86_64-static"
SYSPREFIX="/opt/x86root"

OUT="$SELF_DIR/out"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX_DIR="$OUT/dex"
DIST="$ROOT/dist"
APK="$DIST/RootTerm-selftest.apk"
KEYSTORE="$ROOT/tools/debug.keystore"

CURRENT_STEP="初始化"
die() { echo "" >&2; echo "错误: $*" >&2; exit 1; }
on_error() {
    local rc=$?
    echo "" >&2
    echo "!! 自检失败（步骤: $CURRENT_STEP，退出码 $rc）" >&2
    echo "   先跑 bash tools/setup.sh；仍失败请把上面完整报错发给 toolchain。" >&2
    exit "$rc"
}
trap on_error ERR
step() { CURRENT_STEP="$1"; echo "== $1"; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令 $1；请先运行: bash tools/setup.sh"; }

# ---------------------------------------------------------------------------
step "0/9 前置检查"
need java
need javac
need keytool
need aapt
need zipalign
need python3
[ -f "$JAR36" ] || die "缺少 $JAR36；请先运行: bash tools/setup.sh"
[ -f "$D8_JAR" ] || die "缺少 $D8_JAR；请先运行: bash tools/setup.sh"

# apksigner：优先 apt 的 /usr/bin/apksigner，否则用 build-tools 的 apksigner.jar
APKSIGNER=(apksigner)
if ! command -v apksigner >/dev/null 2>&1; then
    [ -f "$SDK/apksigner.jar" ] || die "缺少 apksigner 命令，也没有 $SDK/apksigner.jar"
    APKSIGNER=(java -cp "$SDK/apksigner.jar" com.android.apksigner.ApkSignerTool)
    echo "    apksigner: 使用 $SDK/apksigner.jar（apt 命令不可用）"
fi

# 选择资源模式（与 build.sh 完全一致）
MODE="aapt1"
if [ -x "$QEMU" ] && [ -f "$AAPT2" ] && [ -d "$SYSPREFIX/usr/lib/x86_64-linux-gnu" ] \
   && "$QEMU" -L "$SYSPREFIX" "$AAPT2" version >/dev/null 2>&1; then
    MODE="aapt2"
fi
echo "    资源模式: $MODE"
if [ "$MODE" = "aapt1" ]; then
    need aapt
    [ -f "$JAR34" ] || die "aapt1 回退需要 $JAR34；请先运行: bash tools/setup.sh"
fi

# ---------------------------------------------------------------------------
step "1/9 清理中间产物"
rm -rf -- "$OUT"
mkdir -p -- "$GEN" "$CLASSES" "$DEX_DIR" "$DIST"

# ---------------------------------------------------------------------------
step "2/9 资源：manifest + res -> base.apk + R.java"
if [ "$MODE" = "aapt2" ]; then
    "$QEMU" -L "$SYSPREFIX" "$AAPT2" compile --dir "$SELF_DIR/res" -o "$OUT/res.zip"
    "$QEMU" -L "$SYSPREFIX" "$AAPT2" link \
        -o "$OUT/base.apk" -I "$JAR36" \
        --manifest "$SELF_DIR/AndroidManifest.xml" \
        --java "$GEN" --min-sdk-version 26 --target-sdk-version 37 \
        "$OUT/res.zip"
else
    aapt package -f -m -J "$GEN" \
        -M "$SELF_DIR/AndroidManifest.xml" -S "$SELF_DIR/res" \
        -I "$JAR34" -F "$OUT/base.apk" \
        --min-sdk-version 26 --target-sdk-version 37
fi

# ---------------------------------------------------------------------------
step "3/9 javac（API 36 类库，Java 8 字节码）"
mapfile -t JAVA_FILES < <(find "$SELF_DIR/src" "$GEN" -type f -name '*.java' | sort)
[ "${#JAVA_FILES[@]}" -gt 0 ] || die "没有找到 .java 源码"
echo "    编译 ${#JAVA_FILES[@]} 个 .java"
javac -source 8 -target 8 -bootclasspath "$JAR36" -encoding UTF-8 \
    -proc:none -nowarn -Xlint:none -d "$CLASSES" "${JAVA_FILES[@]}"

# ---------------------------------------------------------------------------
step "4/9 d8：class -> classes.dex"
mapfile -t CLASS_FILES < <(find "$CLASSES" -type f -name '*.class' | sort)
[ "${#CLASS_FILES[@]}" -gt 0 ] || die "javac 没有产出 .class"
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 26 --lib "$JAR36" \
    --output "$DEX_DIR" "${CLASS_FILES[@]}"
[ -f "$DEX_DIR/classes.dex" ] || die "d8 没有产出 classes.dex"

# ---------------------------------------------------------------------------
step "5/9 把 classes.dex 写入 APK"
python3 - "$OUT/base.apk" "$DEX_DIR/classes.dex" "$OUT/unsigned.apk" <<'PY'
import shutil, sys, zipfile
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY
aapt list "$OUT/unsigned.apk" 2>/dev/null | grep -qx "classes.dex" \
    || die "APK 里没有 classes.dex（打包 dex 失败）"

# ---------------------------------------------------------------------------
step "6/9 zipalign -f -p 4"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ---------------------------------------------------------------------------
step "7/9 确保 debug keystore 存在"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p -- "$(dirname -- "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
else
    echo "    复用 $KEYSTORE"
fi

# ---------------------------------------------------------------------------
step "8/9 apksigner 签名（v1+v2+v3）"
"${APKSIGNER[@]}" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 26 \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$APK" "$OUT/aligned.apk"

# ---------------------------------------------------------------------------
step "9/9 验证"
echo "--- apksigner verify ---"
"${APKSIGNER[@]}" verify --min-sdk-version 26 -v "$APK" | grep -E "Verifies|scheme" || true

BADGING="$(aapt dump badging "$APK")"
echo "--- badging 关键行 ---"
printf '%s\n' "$BADGING" | grep -E "^(package:|sdkVersion:|targetSdkVersion:|application-label:|launchable-activity:)" \
    || die "badging 里缺少关键行"
printf '%s\n' "$BADGING" | grep -q "sdkVersion:'26'" || die "自检 APK minSdk 不是 26"
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'37'" || die "自检 APK targetSdk 不是 37"

echo ""
echo "自检 APK: $APK ($(stat -c %s "$APK") bytes)"
echo "资源模式: $MODE"
echo "SELFTEST OK"
