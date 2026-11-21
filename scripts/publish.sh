#!/usr/bin/env bash
# 一键发布到 GitHub：创建仓库 → push → 建 tag → 发 Release 并上传 APK
#
# 用法：
#   GH_TOKEN=ghp_xxx bash scripts/publish.sh [仓库名] [私有|公开]
#
# 需要：git、curl、已构建好的 dist/*.apk（先跑 bash build.sh）
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$ROOT"

REPO_NAME="${1:-TFManager}"
VISIBILITY="${2:-public}"          # public | private
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
OWNER="${GH_OWNER:-}"

if [ -z "$TOKEN" ]; then
  echo "缺少 Token：请先 export GH_TOKEN=ghp_xxx（需 repo 权限）" >&2
  exit 1
fi

API="https://api.github.com"

# ---------- 0. 取登录用户 ----------
if [ -z "$OWNER" ]; then
  OWNER="$(curl -s -H "Authorization: Bearer $TOKEN" "$API/user" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("login",""))')"
  [ -n "$OWNER" ] || { echo "Token 无效或无法读取用户信息" >&2; exit 1; }
fi
echo "== 账号：$OWNER  仓库：$REPO_NAME（$VISIBILITY）"

# ---------- 1. 创建仓库（已存在则跳过） ----------
CODE=$(curl -s -o /tmp/gh_repo.json -w '%{http_code}' -X POST "$API/user/repos" \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$REPO_NAME\",\"private\":$([ "$VISIBILITY" = private ] && echo true || echo false),\"description\":\"TF管理终端 - Android 文件管理 + root 终端\",\"has_issues\":true,\"has_wiki\":false}")
if [ "$CODE" = "201" ]; then
  echo "== 仓库已创建"
elif [ "$CODE" = "422" ]; then
  echo "== 仓库已存在，继续"
else
  echo "创建仓库失败 HTTP $CODE"; cat /tmp/gh_repo.json; exit 1
fi

# ---------- 2. git 提交并推送 ----------
if [ ! -d .git ]; then
  git init -q
  git config user.name "$OWNER"
  git config user.email "$OWNER@users.noreply.github.com"
fi
git add -A
git diff --cached --quiet || git commit -q -m "build: TF管理终端 $(date +%Y-%m-%d)"
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
[ "$BRANCH" = "HEAD" ] && BRANCH=main
git branch -M "$BRANCH" 2>/dev/null || true
REMOTE="https://x-access-token:${TOKEN}@github.com/${OWNER}/${REPO_NAME}.git"
git remote remove origin 2>/dev/null || true
git remote add origin "$REMOTE"
git push -u origin "$BRANCH" --force
echo "== 已推送代码"

# ---------- 3. 找 APK 并建 Release ----------
APK="$(ls -1t dist/*.apk 2>/dev/null | head -1 || true)"
[ -n "$APK" ] || { echo "没有找到 dist/*.apk，请先 bash build.sh" >&2; exit 1; }
VER="$(python3 - "$APK" <<'PY'
import re,sys
p=sys.argv[1]
m=re.search(r'-([0-9][0-9.]*)\.apk$', p)
print(m.group(1) if m else '1.0')
PY
)"
TAG="v$VER"
echo "== 发布 Release $TAG，附件：$APK"

REL_ID=$(curl -s -X POST "$API/repos/$OWNER/$REPO_NAME/releases" \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -d "{\"tag_name\":\"$TAG\",\"name\":\"TF管理终端 v$VER\",\"body\":\"自动发布 v$VER\",\"draft\":false,\"prerelease\":false}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id",""))')

if [ -z "$REL_ID" ]; then
  echo "== Release 可能已存在，改为上传到已有 Release"
  REL_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$API/repos/$OWNER/$REPO_NAME/releases/tags/$TAG" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id",""))')
fi
[ -n "$REL_ID" ] || { echo "无法创建/获取 Release" >&2; exit 1; }

UP_URL="https://uploads.github.com/repos/$OWNER/$REPO_NAME/releases/$REL_ID/assets?name=$(basename "$APK")"
curl -s -X POST "$UP_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" > /tmp/gh_asset.json
python3 -c 'import json;d=json.load(open("/tmp/gh_asset.json"));print("== APK 已上传：", d.get("browser_download_url", d))'

echo
echo "=============================================="
echo " 仓库：https://github.com/$OWNER/$REPO_NAME"
echo " 发布：https://github.com/$OWNER/$REPO_NAME/releases/tag/$TAG"
echo " 更新源（填到 App 设置→更新源地址）："
echo "   https://api.github.com/repos/$OWNER/$REPO_NAME/releases/latest"
echo "=============================================="
