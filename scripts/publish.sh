#!/usr/bin/env bash
# 一键发布到 GitHub（纯 python3 实现，无需 curl）
#   GH_TOKEN=ghp_xxx bash scripts/publish.sh [仓库名] [public|private]
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
exec python3 scripts/publish.py "$@"
