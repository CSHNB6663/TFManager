#!/usr/bin/env bash
# 只做 Java 类型检查：生成 R.java / BuildKey / BuildCert / BuildAssets 后编译
# app/src/main/java（用 tools/sdk/android.jar 当 bootclasspath），
# 不生成 APK、不写 dist/，方便写完一个面板就先自查。
# 用法: bash tools/typecheck.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

ANDROID_JAR="$ROOT/tools/sdk/android.jar"
JAR34="$ROOT/tools/sdk/android34.jar"
SRC="$ROOT/app/src/main/java"
OUT="$ROOT/tools/out/typecheck"

[ -f "$ANDROID_JAR" ] || { echo "错误: 缺少 $ANDROID_JAR；请先运行: bash tools/setup.sh" >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "错误: 缺少 javac；请先运行: bash tools/setup.sh" >&2; exit 1; }
[ -d "$SRC" ] || { echo "错误: 缺少源码目录 $SRC" >&2; exit 1; }

rm -rf -- "$OUT"
mkdir -p -- "$OUT/gen" "$OUT/classes" "$OUT/src"

# ---- 1. 生成 R.java（与 build.sh 一致：aapt2 优先，回退 aapt1）----
QEMU="/usr/bin/qemu-x86_64-static"
SYSPREFIX="/opt/x86root"
AAPT2="$ROOT/tools/sdk/aapt2"
MODE="aapt1"
if [ -x "$QEMU" ] && [ -f "$AAPT2" ] && [ -d "$SYSPREFIX/usr/lib/x86_64-linux-gnu" ] \
   && "$QEMU" -L "$SYSPREFIX" "$AAPT2" version >/dev/null 2>&1; then
  MODE="aapt2"
fi
echo "== 生成 R.java（$MODE）"
if [ "$MODE" = "aapt2" ]; then
  "$QEMU" -L "$SYSPREFIX" "$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip" >/dev/null
  "$QEMU" -L "$SYSPREFIX" "$AAPT2" link \
    -o "$OUT/base.apk" -I "$ANDROID_JAR" \
    -A "$ROOT/app/src/main/assets" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 37 \
    "$OUT/res.zip" >/dev/null
else
  [ -f "$JAR34" ] || { echo "错误: 缺少 $JAR34（aapt1 回退需要 API 34 框架 jar）" >&2; exit 1; }
  aapt package -f -m -J "$OUT/gen" -M "$ROOT/app/src/main/AndroidManifest.xml" \
    -S "$ROOT/app/src/main/res" -A "$ROOT/app/src/main/assets" \
    -I "$JAR34" -F "$OUT/base.apk" --min-sdk-version 26 --target-sdk-version 37
fi

# ---- 2. 生成 BuildKey / BuildAssets（与 build.sh 同一脚本，保证一致）----
echo "== 生成 BuildKey / BuildAssets"
cp -r "$SRC" "$OUT/src/"
python3 "$ROOT/tools/encstrings.py" "$OUT/src" "$OUT/gen" "$ROOT/app/src/main/assets" >/dev/null

# ---- 3. 生成 BuildCert（构建期注入的签名指纹，typecheck 用占位值）----
cat > "$OUT/gen/com/rootterm/fm/BuildCert.java" <<'EOF'
package com.rootterm.fm;

/** 由构建脚本生成：签名证书 SHA-256 指纹。请勿手改。 */
public final class BuildCert {
    public static final String SHA256 = "typecheck";
    private BuildCert() {
    }
}
EOF

mapfile -t FILES < <(find "$OUT/src" "$OUT/gen" -type f -name '*.java' | sort)
[ "${#FILES[@]}" -gt 0 ] || { echo "没有找到 .java 源码（目录: $SRC）"; exit 0; }

echo "== javac 类型检查（${#FILES[@]} 个文件，不生成 APK）"
if javac -source 8 -target 8 -encoding UTF-8 -proc:none -nowarn -Xlint:none \
        -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" "${FILES[@]}"; then
    echo "TYPECHECK OK（${#FILES[@]} 个文件）"
else
    echo "" >&2
    echo "TYPECHECK FAILED：以上是 javac 报错。工具链没有修改任何源码。" >&2
    exit 1
fi
