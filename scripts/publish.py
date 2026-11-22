#!/usr/bin/env python3
# 一键发布到 GitHub：创建仓库 → push → 建 Release → 上传 APK（纯标准库，无需 curl）
#
# 用法：
#   GH_TOKEN=ghp_xxx python3 scripts/publish.py [仓库名] [public|private]
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
REPO = sys.argv[1] if len(sys.argv) > 1 else "TFManager"
VISIBILITY = sys.argv[2] if len(sys.argv) > 2 else "public"


def call(method, url, payload=None, raw_bytes=None, content_type=None):
    headers = {
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "TFManager-publish",
    }
    body = None
    if raw_bytes is not None:
        body = raw_bytes
        headers["Content-Type"] = content_type or "application/octet-stream"
    elif payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # 网络错误
        return 0, str(e).encode()


def show(code, data, what):
    if 200 <= code < 300:
        return True
    print("  [失败] %s HTTP %s: %s" % (what, code, data[:300].decode("utf-8", "replace")))
    return False


def main():
    if not TOKEN:
        print("缺少 Token：export GH_TOKEN=ghp_xxx 后再运行")
        return 1

    code, data = call("GET", API + "/user")
    if not show(code, data, "读取用户"):
        return 1
    login = json.loads(data)["login"]
    print("== 账号：%s  仓库：%s（%s）" % (login, REPO, VISIBILITY))

    # 1) 先查仓库是否已存在（避免触发创建频率限制）
    code0, data0 = call("GET", API + "/repos/%s/%s" % (login, REPO))
    if 200 <= code0 < 300:
        print("== 仓库已存在，跳过创建")
        repo_ready = True
    else:
        repo_ready = False

    code, data = (0, b"") if repo_ready else call("POST", API + "/user/repos", {
        "name": REPO,
        "private": VISIBILITY == "private",
        "description": "TF管理终端 · Android 文件管理 + root 终端",
        "has_issues": True,
        "has_wiki": False,
    })
    if repo_ready:
        pass
    elif code == 201:
        print("== 仓库已创建")
    elif code == 422:
        print("== 仓库已存在，继续")
    else:
        show(code, data, "创建仓库")
        return 1

    # 2) git 提交（如未提交）并推送
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    subprocess.run(["git", "config", "user.name", login], cwd=ROOT, check=False)
    subprocess.run(["git", "config", "user.email", login + "@users.noreply.github.com"],
                   cwd=ROOT, check=False)
    subprocess.run(["git", "add", "-A"], cwd=ROOT, check=False)
    if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode != 0:
        subprocess.run(["git", "commit", "-q", "-m", "chore: update"], cwd=ROOT, check=False)

    branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT,
                            capture_output=True, text=True).stdout.strip() or "main"
    if branch == "HEAD":
        branch = "main"
    subprocess.run(["git", "branch", "-M", branch], cwd=ROOT, check=False)
    remote = "https://x-access-token:%s@github.com/%s/%s.git" % (TOKEN, login, REPO)
    subprocess.run(["git", "remote", "remove", "origin"], cwd=ROOT, check=False)
    subprocess.run(["git", "remote", "add", "origin", remote], cwd=ROOT, check=True)
    print("== 推送代码…")
    pushed = False
    for attempt in range(1, 7):
        p = subprocess.run(["git", "push", "-u", "origin", branch, "--force"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
        if p.returncode == 0:
            pushed = True
            break
        print("  push 第 %d 次失败：%s" % (attempt, (p.stderr or "").strip()[:120]))
        time.sleep(4)
    if not pushed:
        print("  [失败] git push（github.com 被墙/超时）——可开启 VPN 重试")
        return 1

    # 3) 找 APK
    dist = os.path.join(ROOT, "dist")
    apks = []
    if os.path.isdir(dist):
        apks = [os.path.join(dist, f) for f in os.listdir(dist) if f.endswith(".apk")]
        apks.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    if not apks:
        print("没有 dist/*.apk，请先 bash build.sh")
        return 1
    apk = apks[0]
    apk_name = os.path.basename(apk)
    m = re.search(r"-([0-9][0-9.]*)\.apk$", apk_name)
    ver = m.group(1) if m else "1.0"
    tag = "v" + ver
    print("== 发布 Release %s，附件：%s" % (tag, apk_name))

    code, data = call("POST", API + "/repos/%s/%s/releases" % (login, REPO), {
        "tag_name": tag,
        "name": "TF管理终端 v" + ver,
        "body": "自动发布 v" + ver,
        "draft": False,
        "prerelease": False,
    })
    rel_id = None
    if 200 <= code < 300:
        rel_id = json.loads(data)["id"]
    else:
        # 已存在则复用
        code2, data2 = call("GET", API + "/repos/%s/%s/releases/tags/%s" % (login, REPO, tag))
        if 200 <= code2 < 300:
            rel_id = json.loads(data2)["id"]
            print("== Release 已存在，复用")
    if not rel_id:
        show(code, data, "创建 Release")
        return 1

    # 4) 上传 APK（同名已存在则先删）
    assets_url = API + "/repos/%s/%s/releases/%s/assets" % (login, REPO, rel_id)
    code, data = call("GET", assets_url)
    if 200 <= code < 300:
        for a in json.loads(data):
            if a.get("name") == apk_name:
                call("DELETE", API + "/repos/%s/%s/releases/assets/%d"
                     % (login, REPO, a["id"]))
                print("== 删除同名旧附件")

    with open(apk, "rb") as f:
        blob = f.read()
    up = "https://uploads.github.com/repos/%s/%s/releases/%s/assets?name=%s" % (
        login, REPO, rel_id, apk_name)
    url = ""
    for attempt in range(1, 9):
        code, data = call("POST", up, raw_bytes=blob,
                          content_type="application/vnd.android.package-archive")
        if 200 <= code < 300:
            url = json.loads(data).get("browser_download_url", "")
            print("  上传成功（第 %d 次）" % attempt)
            break
        print("  上传第 %d 次失败：HTTP %s" % (attempt, code))
        time.sleep(3)
    if not url:
        print("  [失败] APK 上传失败（uploads.github.com 超时）——可开启 VPN 重试")
        return 1

    print()
    print("=" * 56)
    print(" 仓库：https://github.com/%s/%s" % (login, REPO))
    print(" 发布：https://github.com/%s/%s/releases/tag/%s" % (login, REPO, tag))
    print(" APK ：%s" % url)
    print(" 更新源（填到 App 设置→更新源地址）：")
    print("   https://api.github.com/repos/%s/%s/releases/latest" % (login, REPO))
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())