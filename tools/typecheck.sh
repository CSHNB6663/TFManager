#!/usr/bin/env bash
# 只做 Java 类型检查：javac 编译 app/src/main/java（用 tools/sdk/android.jar 当 bootclasspath），
# 不生成 APK、不写 dist/，方便写完一个面板就先自查。
# 用法: bash tools/typecheck.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

ANDROID_JAR="$ROOT/tools/sdk/android.jar"
SRC="$ROOT/app/src/main/java"
OUT="$ROOT/tools/out/typecheck"

[ -f "$ANDROID_JAR" ] || { echo "错误: 缺少 $ANDROID_JAR；请先运行: bash tools/setup.sh" >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "错误: 缺少 javac；请先运行: bash tools/setup.sh" >&2; exit 1; }
[ -d "$SRC" ] || { echo "错误: 缺少源码目录 $SRC" >&2; exit 1; }

rm -rf -- "$OUT"
mkdir -p -- "$OUT"

mapfile -t FILES < <(find "$SRC" -type f -name '*.java' | sort)
[ "${#FILES[@]}" -gt 0 ] || { echo "没有找到 .java 源码（目录: $SRC）"; exit 0; }

echo "== javac 类型检查（${#FILES[@]} 个文件，不生成 APK）"
if javac -source 8 -target 8 -encoding UTF-8 -proc:none -nowarn -Xlint:none \
        -bootclasspath "$ANDROID_JAR" -d "$OUT" "${FILES[@]}"; then
    echo "TYPECHECK OK（${#FILES[@]} 个文件）"
else
    echo "" >&2
    echo "TYPECHECK FAILED：以上是 javac 报错。工具链没有修改任何源码。" >&2
    exit 1
fi
