#!/usr/bin/env bash
# 打包为 KernelSU 模块（不发布、不改真实 system 分区）
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$ROOT"

echo "== [1/3] 构建 APK"
bash build.sh >/tmp/ksu-build.log 2>&1 || { tail -20 /tmp/ksu-build.log; exit 1; }
APK="$(ls -1t dist/*.apk | head -1)"
echo "   APK: $APK"

VER="$(python3 - "$APK" <<'PY'
import re,sys
m=re.search(r'-([0-9][0-9.]*)\.apk$', sys.argv[1])
print(m.group(1) if m else '0')
PY
)"

echo "== [2/3] 组装模块"
MOD="$ROOT/ksu-module"
mkdir -p "$MOD/system/app/TFManager"
cp -f "$APK" "$MOD/system/app/TFManager/TFManager.apk"
# 同步版本号到 module.prop
python3 - "$MOD/module.prop" "$VER" <<'PY'
import re,sys
p,ver=sys.argv[1],sys.argv[2]
s=open(p,encoding='utf-8').read()
s=re.sub(r'^version=.*$', 'version=v'+ver, s, flags=re.M)
code=ver.replace('.','')
s=re.sub(r'^versionCode=.*$', 'versionCode='+code, s, flags=re.M)
open(p,'w',encoding='utf-8').write(s)
print('  module.prop → v'+ver)
PY

echo "== [3/3] 打包 zip"
OUT="/sdcard/Download/TF管理终端-KSU模块-v$VER.zip"
rm -f "$OUT"
( cd "$MOD" && zip -r -q -9 "$OUT" . -x '*.DS_Store' )
ls -lh "$OUT"
echo "MODULE OK: $OUT"
